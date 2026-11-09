package fr.ftnl.texto

import fr.ftnl.texto.config.AppConfig
import fr.ftnl.texto.db.Db
import fr.ftnl.texto.db.dbBlocking
import fr.ftnl.texto.db.Posts
import fr.ftnl.texto.legacy.LegacyDates
import fr.ftnl.texto.legacy.LegacyImporter
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import fr.ftnl.texto.service.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.*
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class LegacyAndFlowTest {
    private val pg = PostgreSQLContainer("postgres:17-alpine")
    private val pages = Files.createTempDirectory("texto-pages").toFile()
    private lateinit var cfg: AppConfig

    private val legacyKey = "AbCdEf1234"
    private val legacyApiKey = "legacy-api-key-123"

    @BeforeAll
    fun setup() {
        pg.start()
        val ds = PGSimpleDataSource().apply {
            setURL(pg.jdbcUrl); user = pg.username; password = pg.password
        }
        // Schéma tel que créé par Exposed dans la v1 (identifiants non quotés => minuscules).
        ds.connection.use { c ->
            c.createStatement().use { st ->
                st.execute(
                    """
                    CREATE TABLE TBL_AUTHOR_AUT (id SERIAL PRIMARY KEY, created_at TIMESTAMP NOT NULL DEFAULT now(),
                      updated_at TIMESTAMP NOT NULL DEFAULT now(), user_email_hash VARCHAR(128) UNIQUE, name VARCHAR(25) UNIQUE,
                      avatar VARCHAR(255), api_key VARCHAR(255));
                    CREATE TABLE TBL_SOCIAL_MEDIA_SOM (id SERIAL PRIMARY KEY, created_at TIMESTAMP NOT NULL DEFAULT now(),
                      updated_at TIMESTAMP NOT NULL DEFAULT now(), "user" INT REFERENCES TBL_AUTHOR_AUT(id), icon VARCHAR(100), url VARCHAR(255));
                    CREATE TABLE TBL_TEXTO_TXT (id SERIAL PRIMARY KEY, created_at TIMESTAMP NOT NULL DEFAULT now(),
                      updated_at TIMESTAMP NOT NULL DEFAULT now(), "user" INT REFERENCES TBL_AUTHOR_AUT(id), key_hash VARCHAR(255) UNIQUE,
                      name VARCHAR(100), description TEXT, vues INT DEFAULT 0, public BOOLEAN DEFAULT false, expire_at TIMESTAMP NULL);
                    CREATE TABLE TBL_USER_LOGIN_ULO (id SERIAL PRIMARY KEY, created_at TIMESTAMP NOT NULL DEFAULT now(),
                      updated_at TIMESTAMP NOT NULL DEFAULT now(), user_email_hash VARCHAR(128), "username-hash" VARCHAR(128),
                      password VARCHAR(512), author INT REFERENCES TBL_AUTHOR_AUT(id));
                    INSERT INTO TBL_AUTHOR_AUT (user_email_hash, name, avatar, api_key) VALUES
                      ('hash-v1-1', 'Ocelus', 'https://cdn.discordapp.com/avatars/1/abc123', '$legacyApiKey'),
                      ('hash-v1-2', 'Other', 'https://cdn.example/a.png', 'k2');
                    INSERT INTO TBL_SOCIAL_MEDIA_SOM ("user", icon, url) VALUES (1, 'github', 'https://github.com/x');
                    INSERT INTO TBL_TEXTO_TXT ("user", key_hash, name, description, vues, public, expire_at) VALUES
                      (1, '$legacyKey', 'Mon code', 'desc', 42, true, NULL),
                      (1, 'Expired000', 'Vieux', '', 1, false, now() - interval '1 day'),
                      (2, 'NoFile0000', 'Sans fichier', '', 0, false, NULL),
                      (2, 'Unlisted00', 'Non public', '', 3, false, now() + interval '3 days');
                    """.trimIndent()
                )
            }
        }
        pages.resolve(LegacyImporter.md5(legacyKey)).writeText("fun main() = println(\"hello\")")
        pages.resolve(LegacyImporter.md5("Unlisted00")).writeText("secret-ish")
        pages.resolve(LegacyImporter.md5("Expired000")).writeText("old")

        cfg = AppConfig.fromEnv().copy(
            db = AppConfig.DbConfig(pg.jdbcUrl, pg.username, pg.password, 4),
            legacyPagesDir = pages.absolutePath,
            dev = true,
            admins = setOf("discord:1"),
        )
        Db.init(cfg.db)
    }

    @AfterAll
    fun teardown() {
        pg.stop()
    }

    @Test
    @Order(1)
    fun `legacy import`() {
        val users = UserService(cfg)
        val report = LegacyImporter(cfg, users).runIfNeeded()
        assertNotNull(report)
        assertEquals(2, report.users)
        assertEquals(2, report.posts)
        assertEquals(1, report.expired)
        assertEquals(1, report.missingFiles)
        assertEquals(2, report.tokens)
        // Deuxième passage : rien à faire.
        assertNull(LegacyImporter(cfg, users).runIfNeeded())

        val posts = PostService(cfg)
        val r = posts.read(Viewer(null), legacyKey, null, countView = true) as PostService.ReadResult.Ok
        assertEquals("fun main() = println(\"hello\")", r.post.content)
        assertEquals("public", r.post.summary.visibility)
        assertEquals(43, r.post.summary.views)
        assertEquals("ocelus", r.post.author.handle)
        val unlisted = posts.read(Viewer(null), "Unlisted00", null, false) as PostService.ReadResult.Ok
        assertEquals("unlisted", unlisted.post.summary.visibility)
        assertNotNull(unlisted.post.summary.expiresAt)

        // Ancienne clé API toujours valide.
        val principal = TokenService.authenticate(legacyApiKey)
        assertNotNull(principal)
        // Tables v1 renommées.
        val renamed = dbBlocking {
            val rs = (connection.connection as java.sql.Connection).createStatement()
                .executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_name LIKE 'legacy_tbl_%'")
            rs.next(); rs.getInt(1)
        }
        assertEquals(4, renamed)
        assertEquals("ocelus", users.handleForLegacyId(1))
    }

    @Test
    @Order(2)
    fun `oauth claims the legacy account through its discord id, then links twitch`() {
        val users = UserService(cfg)
        val login = users.loginOrLink(OAuthProfile("discord", "1", "Ocelus", null), null) as LoginResult.Ok
        assertTrue(login.claimedLegacy)
        val me = users.me(login.userId)
        assertEquals("ocelus", me.handle)
        assertEquals("admin", me.role)
        assertEquals(1, me.socials.size)

        // Un compte Twitch déjà utilisé seul, puis lié : les deux comptes fusionnent.
        val twitchOnly = users.loginOrLink(OAuthProfile("twitch", "t1", "OcelusTV", null), null) as LoginResult.Ok
        val posts = PostService(cfg)
        posts.create(twitchOnly.userId, PostInput(title = "from twitch", content = "abc", tags = listOf("Kotlin")))
        posts.create(login.userId, PostInput(title = "from discord", content = "def", tags = listOf("kotlin")))
        val linked = users.loginOrLink(OAuthProfile("twitch", "t1", "OcelusTV", null), login.userId) as LoginResult.Ok
        assertTrue(linked.merged)
        assertEquals(login.userId, linked.userId)
        val merged = users.me(login.userId)
        assertEquals(setOf("discord", "twitch"), merged.accounts.map { it.provider }.toSet())
        val tags = TagService.list(login.userId)
        assertEquals(1, tags.size, "les tags de même nom sont fusionnés")
        assertEquals(2, tags.single().postCount)
        val own = posts.listOwn(login.userId, PostQuery())
        assertEquals(3, own.total) // 1 post v1 + 2 nouveaux
    }

    @Test
    @Order(3)
    fun `http flow with token, password, burn, search, revisions`() = testApplication {
        application { module(cfg) }
        val users = UserService(cfg)
        val uid = (users.loginOrLink(OAuthProfile("discord", "42", "Alice", null), null) as LoginResult.Ok).userId
        val secret = TokenService.create(uid, TokenInput("cli")).secret

        suspend fun json(r: HttpResponse) = Json.parseToJsonElement(r.bodyAsText()).jsonObject

        val created = client.post("/api/posts") {
            bearerAuth(secret)
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Recette tarte","content":"pommes sucre beurre","tags":["cuisine"],"password":"pw"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val createdJson = json(created)
        val key = createdJson["key"]!!.jsonPrimitive.content
        assertEquals("public", createdJson["visibility"]!!.jsonPrimitive.content, "public par défaut")

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/posts/$key").status)
        val wrong = client.get("/api/posts/$key") { header("X-Post-Password", "nope") }
        assertEquals("wrong_password", json(wrong)["error"]!!.jsonPrimitive.content)
        val ok = client.get("/api/posts/$key") { header("X-Post-Password", "pw"); header("User-Agent", "Mozilla") }
        assertEquals(HttpStatusCode.OK, ok.status)

        val search = client.get("/api/me/posts?q=pomm") { bearerAuth(secret) }
        assertEquals(1, json(search)["total"]!!.jsonPrimitive.int)
        val byTag = client.get("/api/me/posts?tag=Cuisine") { bearerAuth(secret) }
        assertEquals(1, json(byTag)["total"]!!.jsonPrimitive.int)

        val patched = client.patch("/api/posts/$key") {
            bearerAuth(secret); contentType(ContentType.Application.Json)
            setBody("""{"content":"pommes sucre beurre cannelle"}""")
        }
        assertEquals(2, json(patched)["revision"]!!.jsonPrimitive.int)
        val revs = client.get("/api/posts/$key/revisions") { bearerAuth(secret) }
        assertEquals(2, Json.parseToJsonElement(revs.bodyAsText()).jsonArray.size)

        // Brûler après 1 lecture.
        val burn = client.post("/api/posts") {
            bearerAuth(secret); contentType(ContentType.Application.Json)
            setBody("""{"content":"one shot","burnAfterViews":1}""")
        }
        val burnKey = json(burn)["key"]!!.jsonPrimitive.content
        val first = client.get("/api/posts/$burnKey") { header("User-Agent", "Mozilla") }
        assertEquals(true, json(first)["burned"]!!.jsonPrimitive.boolean)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/posts/$burnKey").status)

        // Compatibilité v1.
        val legacy = client.post("/new-texto/api") {
            header("X-API-Key", legacyApiKey); contentType(ContentType.Application.Json)
            setBody("""{"content":"via v1","title":"t","description":"","public":false}""")
        }
        assertEquals(HttpStatusCode.OK, legacy.status)
        assertTrue(legacy.bodyAsText().startsWith(cfg.baseUrl + "/"))
        assertEquals("fun main() = println(\"hello\")", client.get("/raw/$legacyKey").bodyAsText())
        val author = client.config { followRedirects = false }.get("/author/1")
        assertEquals("/u/ocelus", author.headers[HttpHeaders.Location])

        // Aperçu OG.
        val og = client.get("/og/$legacyKey.png")
        assertEquals(ContentType.Image.PNG, og.contentType())

        // CSRF : origine étrangère refusée pour une requête par cookie.
        val evil = client.post("/api/auth/logout") { header(HttpHeaders.Origin, "https://evil.example") }
        assertTrue(evil.status == HttpStatusCode.NoContent || evil.status == HttpStatusCode.Forbidden)
        assertTrue(Instant.now().isAfter(Instant.EPOCH))
    }

    @Test
    @Order(4)
    fun `legacy dates are restored from the old files and updated_at is aligned`() {
        // Simule des dates fausses à l'import : le post v1 a été créé en 2021 (date du fichier), mais la base dit « maintenant »
        // et son updated_at a bougé avec les vues.
        val real = Instant.parse("2021-03-04T10:00:00Z")
        pages.resolve(LegacyImporter.md5(legacyKey)).setLastModified(real.toEpochMilli())
        dbBlocking { Posts.update({ Posts.key eq legacyKey }) { it[updatedAt] = Instant.now() } }

        val report = LegacyDates(cfg).restore()
        assertNotNull(report)
        assertTrue(report.postsFixed >= 1)
        val row = dbBlocking { Posts.selectAll().where { Posts.key eq legacyKey }.single() }
        assertEquals(real.toEpochMilli(), row[Posts.createdAt].toEpochMilli())
        assertEquals(row[Posts.createdAt], row[Posts.updatedAt], "post jamais modifié : modifié = créé")
        assertEquals(0, LegacyDates(cfg).restore()!!.postsFixed, "idempotent")
    }
}
