package fr.ftnl.texto

import fr.ftnl.texto.util.FactorioVersion
import fr.ftnl.texto.auth.ProviderDirectory
import fr.ftnl.texto.auth.ProviderProfile
import fr.ftnl.texto.config.AppConfig
import fr.ftnl.texto.db.Db
import fr.ftnl.texto.db.LinkedAccounts
import fr.ftnl.texto.db.Posts
import fr.ftnl.texto.db.PostRevisions
import fr.ftnl.texto.db.PostTags
import fr.ftnl.texto.db.Users
import fr.ftnl.texto.db.dbBlocking
import fr.ftnl.texto.service.*
import fr.ftnl.texto.util.ApiException
import fr.ftnl.texto.util.SocialIcons
import fr.ftnl.texto.util.SocialRules
import fr.ftnl.texto.util.TokenCipher
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.*
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SocialRulesTest {
    @Test
    fun `fontawesome classes and aliases are normalized`() {
        assertEquals("twitch", SocialIcons.normalize("fa-brands fa-twitch"))
        assertEquals("x", SocialIcons.normalize("fa-brands fa-x-twitter"))
        assertEquals("x", SocialIcons.normalize("twitter"))
        assertEquals("website", SocialIcons.normalize("fa-solid fa-link"))
        assertEquals("website", SocialIcons.resolve("fa-brands fa-patreon"), "icône inconnue = lien web")
        assertEquals("spotify", SocialIcons.resolve("fa-brands fa-spotify"))
        assertEquals("github", SocialIcons.normalize("GitHub"))
        assertEquals("youtube", SocialIcons.normalize("", "https://www.youtube.com/@x"))
        assertEquals("X", SocialIcons.label("x"))
    }

    @Test
    fun `each icon only accepts its own links`() {
        assertTrue(SocialRules.valid("twitch", "https://twitch.tv/as2pik"))
        assertTrue(SocialRules.valid("twitch", "https://www.twitch.tv/as2pik"))
        assertFalse(SocialRules.valid("discord", "https://twitch.tv/as2pik"), "lien Twitch sur l'icône Discord")
        assertFalse(SocialRules.valid("twitch", "https://twitch.tv/"), "page d'accueil sans compte")
        assertFalse(SocialRules.valid("twitch", "https://eviltwitch.tv/x"))
        assertFalse(SocialRules.valid("twitch", "http://twitch.tv/x"), "https obligatoire")
        assertTrue(SocialRules.valid("x", "https://twitter.com/OcelusPRO"))
        assertTrue(SocialRules.valid("x", "https://x.com/OcelusPRO"))
        assertTrue(SocialRules.valid("discord", "https://discord.gg/abc123"))
        assertTrue(SocialRules.valid("discord", "https://discord.com/invite/abc123"))
        assertFalse(SocialRules.valid("discord", "https://discord.com/users/1"))
        assertTrue(SocialRules.valid("website", "http://ftnl.fr"))
        assertFalse(SocialRules.valid("website", "javascript:alert(1)"))
        assertTrue(SocialRules.valid("mastodon", "https://mastodon.social/@x"))
        assertFalse(SocialRules.valid("mastodon", "https://mastodon.social/about"))
        assertEquals(setOf("twitch", "youtube", "github", "discord"), SocialRules.ALL.filter { it.embed }.map { it.key }.toSet())
    }

    @Test
    fun `tokens are encrypted and unreadable with another key`() {
        val c = TokenCipher("secret-a")
        val enc = c.encrypt("refresh-token-123")
        assertNotEquals("refresh-token-123", enc)
        assertEquals("refresh-token-123", c.decrypt(enc))
        assertNull(TokenCipher("secret-b").decrypt(enc))
    }
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProfileAndEmbedTest {
    private val pg = PostgreSQLContainer("postgres:17-alpine")
    private lateinit var cfg: AppConfig

    private class FakeDirectory(var profile: ProviderProfile?) : ProviderDirectory {
        var calls = 0
        var lastTokens: StoredTokens? = null
        override suspend fun fetch(provider: String, providerUserId: String, tokens: StoredTokens?): ProviderProfile? {
            calls++
            lastTokens = tokens
            return profile
        }
    }

    @BeforeAll
    fun setup() {
        pg.start()
        val ds = PGSimpleDataSource().apply { setURL(pg.jdbcUrl); user = pg.username; password = pg.password }
        cfg = AppConfig.fromEnv().copy(
            db = AppConfig.DbConfig(pg.jdbcUrl, pg.username, pg.password, 4), dev = true, devLogin = true,
            baseUrl = "https://texto.test", admins = setOf("dev:boss"),
        )
        Db.init(ds)
    }

    @AfterAll
    fun teardown() = pg.stop()

    private fun newUser(service: UserService, provider: String, id: String, name: String, tokens: StoredTokens? = null) =
        (service.loginOrLink(OAuthProfile(provider, id, name, "https://cdn/$name.png", tokens), null) as LoginResult.Ok).userId

    private fun handleOf(id: Long) = dbBlocking { Users.selectAll().where { Users.id eq id }.single()[Users.handle] }
    private fun nameOf(id: Long) = dbBlocking { Users.selectAll().where { Users.id eq id }.single()[Users.displayName] }

    @Test
    fun `daily refresh renames user, keeps old handle as redirect, once per day`() = runBlocking {
        val users = UserService(cfg)
        val id = newUser(users, "twitch", "t-100", "OldName")
        assertEquals("oldname", handleOf(id))
        val dir = FakeDirectory(ProviderProfile("NewName", "https://cdn/new.png"))
        val sync = ProfileSync(users, dir)

        assertTrue(sync.syncIfDue(id))
        assertEquals("NewName", nameOf(id))
        assertEquals("newname", handleOf(id))
        assertEquals("newname", users.canonicalHandle("oldname"), "l'ancien identifiant redirige")
        assertEquals("newname", users.publicProfile("oldname").handle)

        assertFalse(sync.syncIfDue(id))
        assertEquals(1, dir.calls)
        assertTrue(sync.syncIfDue(id, Instant.now().plus(Duration.ofHours(25))))
        val other = newUser(users, "twitch", "t-101", "OldName")
        assertTrue(handleOf(other) != "oldname")
    }

    @Test
    fun `a user who saved their settings is no longer modified automatically`() = runBlocking {
        val users = UserService(cfg)
        val id = newUser(users, "twitch", "t-200", "Custom")
        users.updateMe(id, MePatch(handle = "mon-id", displayName = "Custom"))
        val sync = ProfileSync(users, FakeDirectory(ProviderProfile("Renamed", "https://cdn/r.png")))
        assertTrue(sync.syncIfDue(id))
        assertEquals("Custom", nameOf(id))
        assertEquals("mon-id", handleOf(id))
        assertEquals("Renamed", users.me(id).accounts.single().username)
    }

    @Test
    fun `oauth tokens are stored encrypted and used for the refresh, renewed tokens are kept`() = runBlocking {
        val users = UserService(cfg)
        val expires = Instant.now().plusSeconds(3600)
        val id = newUser(users, "discord", "d-tok", "TokUser", StoredTokens("access-1", expires, "refresh-1"))
        val raw = dbBlocking { LinkedAccounts.selectAll().where { LinkedAccounts.user eq id }.single() }
        assertNotEquals("refresh-1", raw[LinkedAccounts.refreshToken], "jamais en clair en base")
        assertNotEquals("access-1", raw[LinkedAccounts.accessToken])
        assertEquals("refresh-1", users.storedTokens(raw)?.refreshToken)

        val dir = FakeDirectory(ProviderProfile("TokUser2", null, StoredTokens("access-2", expires, "refresh-2")))
        assertTrue(ProfileSync(users, dir).syncIfDue(id))
        assertEquals("refresh-1", dir.lastTokens?.refreshToken, "l'annuaire reçoit les jetons enregistrés")
        val after = dbBlocking { LinkedAccounts.selectAll().where { LinkedAccounts.user eq id }.single() }
        assertEquals("refresh-2", users.storedTokens(after)?.refreshToken, "jeton renouvelé enregistré")
    }

    @Test
    fun `linking merges the existing account of the other provider into the current one`() {
        val users = UserService(cfg)
        val posts = PostService(cfg)
        val a = newUser(users, "discord", "d-merge", "Alice")
        val b = newUser(users, "twitch", "t-merge", "AliceTV")
        posts.create(a, PostInput(title = "post A", content = "a", tags = listOf("kotlin")))
        posts.create(b, PostInput(title = "post B", content = "b", tags = listOf("Kotlin", "twitch")))
        val bHandle = handleOf(b)

        val r = users.loginOrLink(OAuthProfile("twitch", "t-merge", "AliceTV", null, StoredTokens("acc", Instant.now().plusSeconds(60), "ref")), a) as LoginResult.Ok
        assertTrue(r.merged)
        assertEquals(a, r.userId)
        assertEquals(setOf("discord", "twitch"), users.me(a).accounts.map { it.provider }.toSet())
        assertEquals(2, posts.listOwn(a, PostQuery()).total)
        assertEquals(2, TagService.list(a).size, "kotlin fusionné, twitch ajouté")
        assertNull(users.canonicalHandle("zzz-inconnu"))
        assertEquals(users.me(a).handle, users.canonicalHandle(bHandle), "l'ancien identifiant du compte absorbé redirige")
        assertEquals(0, dbBlocking { Users.selectAll().where { Users.id eq b }.count() })
    }

    private fun legacyUser(handle: String, legacyId: Int, avatar: String?): Long = dbBlocking {
        Users.insertAndGetId {
            it[Users.handle] = handle
            it[displayName] = handle
            it[avatarUrl] = avatar
            it[Users.legacyId] = legacyId
            it[createdAt] = Instant.now()
        }.value
    }

    private fun exists(id: Long) = dbBlocking { Users.selectAll().where { Users.id eq id }.count() } == 1L

    @Test
    fun `a v1 account is claimed through its Discord id or Twitch picture, at login, at link time or at a later login`() {
        val users = UserService(cfg)
        val uuid = "c6ca3f86-df8d-46df-97ce-edd58f1c7367"

        // 1. Compte v1 créé via Discord (URL d'avatar avec l'ID Discord) : réclamé par ID.
        val legacyDiscord = legacyUser("v1-discord", 9101, "https://cdn.discordapp.com/avatars/777000/abcdef")
        val r1 = users.loginOrLink(OAuthProfile("discord", "777000", "Nouveau", null), null) as LoginResult.Ok
        assertTrue(r1.claimedLegacy)
        assertEquals(legacyDiscord, r1.userId, "on se retrouve sur le compte v1")

        // 2. Compte v1 créé via Twitch (image de profil) : fusionné quand le même Twitch est lié à un autre compte.
        val legacyTwitch = legacyUser("arftnl-v1", 9102, "https://static-cdn.jtvnw.net/jtv_user_pictures/$uuid-profile_image-300x300.png")
        val me = newUser(users, "discord", "d-link-me", "Moi")
        val r2 = users.loginOrLink(OAuthProfile("twitch", "t-arftnl", "ArFTNL", "https://static-cdn.jtvnw.net/jtv_user_pictures/$uuid-profile_image-600x600.png"), me) as LoginResult.Ok
        assertTrue(r2.claimedLegacy)
        assertFalse(exists(legacyTwitch), "le compte v1 a été absorbé")
        assertEquals(me, users.loginOrLink(OAuthProfile("twitch", "t-arftnl", "ArFTNL", null), null).let { (it as LoginResult.Ok).userId })

        // 3. Le compte v1 n'était pas reconnaissable lors de la liaison ; il le devient plus tard : absorbé à la connexion suivante.
        val other = newUser(users, "twitch", "t-late", "Late", null)
        val uuid2 = "11111111-2222-3333-4444-555555555555"
        val late = legacyUser("late-v1", 9103, "https://static-cdn.jtvnw.net/jtv_user_pictures/$uuid2-profile_image-300x300.png")
        val relog = users.loginOrLink(OAuthProfile("twitch", "t-late", "Late", "https://static-cdn.jtvnw.net/jtv_user_pictures/$uuid2-profile_image-300x300.png"), null) as LoginResult.Ok
        assertEquals(other, relog.userId)
        assertTrue(relog.claimedLegacy)
        assertFalse(exists(late))

        // 4. Pas de correspondance : le compte v1 reste intact.
        val untouched = legacyUser("autre-v1", 9104, "https://static-cdn.jtvnw.net/jtv_user_pictures/99999999-8888-7777-6666-555555555555-profile_image-300x300.png")
        users.loginOrLink(OAuthProfile("twitch", "t-late", "Late", "https://static-cdn.jtvnw.net/jtv_user_pictures/$uuid2-profile_image-300x300.png"), null)
        assertTrue(exists(untouched))
    }

    @Test
    fun `an admin can link a Discord or Twitch id by hand and the person then lands on that account`() {
        val users = UserService(cfg)
        val admin = AdminService(users)
        val adminId = newUser(users, "discord", "d-adm-link", "AdmLink")
        val orphan = legacyUser("orphan-v1", 9201, null)

        // IDs invalides : un pseudo n'est pas un ID.
        assertEquals("invalid_provider_id", assertFailsWith<ApiException> { admin.linkAccount(adminId, orphan, "discord", "pseudo", null, null) }.code)
        assertEquals("invalid_provider_id", assertFailsWith<ApiException> { admin.linkAccount(adminId, orphan, "twitch", "arftnl", null, null) }.code)
        assertEquals("invalid_provider", assertFailsWith<ApiException> { admin.linkAccount(adminId, orphan, "github", "123", null, null) }.code)

        admin.linkAccount(adminId, orphan, "discord", "123456789012345678", null, null)
        admin.linkAccount(adminId, orphan, "twitch", "98765432", "ArFTNL", "https://cdn/a.png")
        val accounts = users.me(orphan).accounts
        assertEquals(setOf("discord", "twitch"), accounts.map { it.provider }.toSet())
        assertEquals("ArFTNL", accounts.single { it.provider == "twitch" }.username)

        // Conflits : ID déjà lié à un autre compte (on indique lequel), et un seul compte par fournisseur.
        val other = newUser(users, "twitch", "t-other-link", "Other")
        val taken = assertFailsWith<ApiException> { admin.linkAccount(adminId, other, "discord", "123456789012345678", null, null) }
        assertEquals("already_linked", taken.code)
        assertEquals(handleOf(orphan), taken.message)
        assertEquals("provider_already_linked", assertFailsWith<ApiException> { admin.linkAccount(adminId, orphan, "discord", "223456789012345678", null, null) }.code)

        // La personne se connecte avec cet ID : elle arrive sur le compte lié, sans création de compte.
        val login = users.loginOrLink(OAuthProfile("discord", "123456789012345678", "VraiPseudo", null), null) as LoginResult.Ok
        assertEquals(orphan, login.userId)

        // Retrait d'une liaison.
        admin.unlinkAccount(adminId, orphan, "twitch")
        assertEquals(listOf("discord"), users.me(orphan).accounts.map { it.provider })
        assertFailsWith<ApiException> { admin.unlinkAccount(adminId, orphan, "twitch") }
    }

    @Test
    fun `only admins can link accounts through the API`() = testApplication {
        application { module(cfg.copy(baseUrl = "http://localhost")) }
        val users = UserService(cfg)
        val orphan = legacyUser("orphan-http", 9202, null)
        suspend fun client() = createClient { install(io.ktor.client.plugins.cookies.HttpCookies); followRedirects = false }
        val body = """{"provider":"discord","providerUserId":"323456789012345678"}"""

        val plain = client()
        plain.get("/login/dev?name=plainlinker")
        assertEquals(HttpStatusCode.Forbidden, plain.post("/api/admin/users/$orphan/accounts") { contentType(ContentType.Application.Json); setBody(body) }.status)

        val admin = client()
        admin.get("/login/dev?name=boss")
        assertEquals(HttpStatusCode.BadRequest, admin.post("/api/admin/users/$orphan/accounts") { contentType(ContentType.Application.Json); setBody("""{"provider":"discord","providerUserId":"pseudo"}""") }.status)
        assertEquals(HttpStatusCode.NoContent, admin.post("/api/admin/users/$orphan/accounts") { contentType(ContentType.Application.Json); setBody(body) }.status)
        assertEquals(listOf("discord"), users.me(orphan).accounts.map { it.provider })
        val list = Json.parseToJsonElement(admin.get("/api/admin/users?q=orphan-http").bodyAsText()).jsonObject["items"]!!.jsonArray
        assertEquals("323456789012345678", list.single().jsonObject["accounts"]!!.jsonArray.single().jsonObject["providerUserId"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/admin/users/$orphan/accounts/discord").status)
        assertTrue(users.me(orphan).accounts.isEmpty())
    }

    @Test
    fun `public profile shows folders with public content only, root lists unfiled posts, search crosses folders`() {
        val users = UserService(cfg)
        val posts = PostService(cfg)
        val id = newUser(users, "twitch", "t-folders", "Folderer")
        val handle = handleOf(id)
        val plans = FolderService.create(id, FolderInput(name = "Plans")).id
        val trains = FolderService.create(id, FolderInput(name = "Trains", parentId = plans)).id
        val secret = FolderService.create(id, FolderInput(name = "Secret")).id
        val empty = FolderService.create(id, FolderInput(name = "Vide")).id
        posts.create(id, PostInput(title = "Racine", content = "x", visibility = "public"))
        posts.create(id, PostInput(title = "Dans plans", content = "x", visibility = "public", folderId = plans))
        posts.create(id, PostInput(title = "Privé dans plans", content = "x", visibility = "private", folderId = plans))
        posts.create(id, PostInput(title = "Gare", content = "x", visibility = "public", folderId = trains))
        posts.create(id, PostInput(title = "Caché", content = "x", visibility = "private", folderId = secret))
        fun titles(page: PageDto<PostSummaryDto>) = page.items.map { it.title }

        // Seuls les dossiers qui contiennent du public (directement ou dedans) sont visibles ; leur nom privé reste caché.
        val tree = FolderService.publicTree(handle)
        assertEquals(setOf("Plans", "Trains"), tree.map { it.name }.toSet())
        assertEquals(2L, tree.single { it.name == "Plans" }.postCount, "récursif : Dans plans + Gare")
        assertEquals(1L, tree.single { it.name == "Plans" }.directCount)

        // Racine : seulement les posts sans dossier. Dossier : seulement ses posts directs.
        assertEquals(listOf("Racine"), titles(posts.listPublicOf(handle, 1, 30, null)))
        assertEquals(listOf("Dans plans"), titles(posts.listPublicOf(handle, 1, 30, null, plans)))
        assertEquals(listOf("Gare"), titles(posts.listPublicOf(handle, 1, 30, null, trains)))

        // Un dossier sans contenu public n'existe pas pour le public.
        assertFailsWith<ApiException> { posts.listPublicOf(handle, 1, 30, null, secret) }
        assertFailsWith<ApiException> { posts.listPublicOf(handle, 1, 30, null, empty) }

        // La recherche traverse les dossiers, sans jamais révéler un post privé.
        assertEquals(listOf("Gare"), titles(posts.listPublicOf(handle, 1, 30, "gare")))
        assertEquals(listOf("Dans plans"), titles(posts.listPublicOf(handle, 1, 30, "plans")))
        assertTrue(titles(posts.listPublicOf(handle, 1, 30, "caché")).isEmpty())
    }

    @Test
    fun `a root tag includes its sub-tags, counts follow the tree and renaming moves the children`() {
        val users = UserService(cfg)
        val posts = PostService(cfg)
        val id = newUser(users, "twitch", "t-tagtree", "TagTree")
        val handle = handleOf(id)
        posts.create(id, PostInput(title = "A", content = "x", visibility = "public", tags = listOf("factorio/base")))
        posts.create(id, PostInput(title = "B", content = "x", visibility = "public", tags = listOf("Factorio/space", "autre")))
        posts.create(id, PostInput(title = "C", content = "x", visibility = "private", tags = listOf("factorio")))
        posts.create(id, PostInput(title = "D", content = "x", visibility = "public", tags = listOf("factorio-fan")))
        fun own(vararg t: String) = posts.listOwn(id, PostQuery(tags = t.toList())).items.map { it.title }.sorted()

        // Les ancêtres sont créés ; la racine regroupe tout son sous-arbre (pas « factorio-fan »).
        assertEquals(listOf("A", "B", "C"), own("factorio"))
        assertEquals(listOf("A"), own("factorio/base"))
        assertEquals(listOf("B"), own("factorio/space"))
        assertEquals(listOf("B"), own("factorio", "autre"))
        assertTrue(own("inconnu").isEmpty())
        val tags = TagService.list(id).associate { it.name.lowercase() to it.postCount }
        assertEquals(3L, tags["factorio"])
        assertEquals(1L, tags["factorio/base"])

        // Profil public : seulement les posts publics, comptes publics.
        assertEquals(listOf("A", "B"), posts.listPublicOf(handle, 1, 30, null, tags = listOf("factorio")).items.map { it.title }.sorted())
        assertEquals(2L, TagService.publicList(handle).single { it.name.equals("factorio", true) }.postCount)

        // Renommer la racine déplace les sous-tags.
        val root = TagService.list(id).single { it.name.equals("factorio", true) }
        TagService.update(id, root.id, TagInput(name = "usine"))
        assertEquals(listOf("A", "B", "C"), own("usine"))
        assertTrue(TagService.list(id).any { it.name == "usine/base" })
        assertTrue(own("factorio").isEmpty())
    }

    private fun blueprintString(root: String, major: Long, minor: Long, patch: Long): String {
        val version = (major shl 48) or (minor shl 32) or (patch shl 16)
        val json = """{"$root":{"item":"blueprint","version":$version,"entities":[]}}"""
        val deflater = java.util.zip.Deflater()
        deflater.setInput(json.toByteArray())
        deflater.finish()
        val buf = ByteArray(4096)
        val out = java.io.ByteArrayOutputStream()
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        return "0" + java.util.Base64.getEncoder().encodeToString(out.toByteArray())
    }

    @Test
    fun `factorio posts get an automatic factorio major-minor version tag`() {
        val users = UserService(cfg)
        val posts = PostService(cfg)
        val id = newUser(users, "twitch", "t-fver", "FVer")
        assertEquals("2.0", FactorioVersion.of(blueprintString("blueprint", 2, 0, 73)))
        assertEquals("1.1", FactorioVersion.of(blueprintString("blueprint_book", 1, 1, 110)))
        assertEquals(null, FactorioVersion.of("0n'est pas un plan"))
        assertEquals(null, FactorioVersion.of("print(1)"))

        val p = posts.create(id, PostInput(title = "Plan", content = blueprintString("blueprint", 1, 1, 110), kind = "factorio", tags = listOf("usine")))
        assertEquals(setOf("usine", "factorio/1.1", "factorio"), posts.listOwn(id, PostQuery()).items.single { it.key == p.key }.tags.map { it.name }.toSet() + "factorio")
        assertEquals(1, posts.listOwn(id, PostQuery(tags = listOf("factorio"))).items.count { it.key == p.key })

        // Le tag suit la version du contenu : l'ancien disparaît (et le tag orphelin avec lui).
        posts.update(Viewer(id), p.key, PostInput(content = blueprintString("blueprint", 2, 0, 5)))
        fun names() = posts.listOwn(id, PostQuery()).items.single { it.key == p.key }.tags.map { it.name }.toSet()
        assertTrue("factorio/2.0" in names() && "factorio/1.1" !in names())
        assertTrue(TagService.list(id).none { it.name == "factorio/1.1" })

        // Impossible à retirer ou à falsifier à la main.
        posts.update(Viewer(id), p.key, PostInput(tags = listOf("usine", "factorio/9.9")))
        assertEquals(setOf("usine", "factorio/2.0"), names())
        val versionTag = TagService.list(id).single { it.name == "factorio/2.0" }
        assertEquals("tag_reserved", assertFailsWith<ApiException> { TagService.delete(id, versionTag.id) }.code)
        assertEquals("tag_reserved", assertFailsWith<ApiException> { TagService.update(id, versionTag.id, TagInput(name = "autre")) }.code)
        assertEquals("tag_reserved", assertFailsWith<ApiException> { TagService.create(id, TagInput(name = "factorio/3.0")) }.code)
        val root = TagService.list(id).single { it.name == "factorio" }
        assertEquals("tag_reserved", assertFailsWith<ApiException> { TagService.update(id, root.id, TagInput(name = "usine2")) }.code)

        // Un post non Factorio n'est pas touché.
        val code = posts.create(id, PostInput(title = "Code", content = "print(1)", kind = "code", tags = listOf("factorio/2.0")))
        assertTrue(posts.listOwn(id, PostQuery()).items.single { it.key == code.key }.tags.isEmpty())
    }

    @Test
    fun `a version is only created when the content changes and redundant old versions collapse back`() {
        val users = UserService(cfg)
        val posts = PostService(cfg)
        val id = newUser(users, "twitch", "t-revs", "Revs")
        val viewer = Viewer(id)
        val p = posts.create(id, PostInput(title = "T", content = "v1 content", kind = "text"))
        fun revs() = posts.revisions(viewer, p.key).map { it.number }

        // Titre, description, type, tags, espaces de fin : aucune nouvelle version.
        posts.update(viewer, p.key, PostInput(title = "Autre", description = "d", tags = listOf("x")))
        posts.update(viewer, p.key, PostInput(content = "v1 content   ", kind = "text"))
        assertEquals(listOf(1), revs())
        assertEquals("Autre", posts.revision(viewer, p.key, 1).title)

        // Un vrai changement de contenu crée la version 2.
        posts.update(viewer, p.key, PostInput(content = "v2 content"))
        assertEquals(listOf(2, 1), revs())

        // Historique déjà pollué (v2 et v3 identiques à v1) : tout revient à v1.
        val q = posts.create(id, PostInput(title = "Q", content = "same", kind = "text"))
        dbBlocking {
            val pid = Posts.selectAll().where { Posts.key eq q.key }.single()[Posts.id].value
            for (n in 2..3) PostRevisions.insert {
                it[post] = pid; it[number] = n; it[title] = "Q"; it[description] = ""; it[kind] = "text"; it[content] = "same"; it[createdAt] = java.time.Instant.now()
            }
            Posts.update({ Posts.id eq pid }) { it[revision] = 3 }
        }
        assertEquals(listOf(3, 2, 1), posts.revisions(viewer, q.key).map { it.number })
        assertTrue(posts.collapseRedundantRevisions() >= 2)
        assertEquals(listOf(1), posts.revisions(viewer, q.key).map { it.number })
        assertEquals(1, posts.listOwn(id, PostQuery()).items.single { it.key == q.key }.revision)
        assertEquals(listOf(2, 1), revs())

        // La date « modifié » ne bouge pas pour une modification de métadonnées ; une date faussée revient à la dernière version.
        val old = java.time.Instant.parse("2024-01-01T00:00:00Z")
        val r = posts.create(id, PostInput(title = "R", content = "r", kind = "text"))
        dbBlocking {
            val pid = Posts.selectAll().where { Posts.key eq r.key }.single()[Posts.id].value
            PostRevisions.update({ PostRevisions.post eq pid }) { it[createdAt] = old }
            Posts.update({ Posts.id eq pid }) { it[createdAt] = old; it[updatedAt] = old }
        }
        posts.update(viewer, r.key, PostInput(title = "R2", description = "x"))
        assertEquals(old.toEpochMilli(), posts.listOwn(id, PostQuery()).items.single { it.key == r.key }.updatedAt)
        dbBlocking { Posts.update({ Posts.key eq r.key }) { it[updatedAt] = java.time.Instant.now() } }
        assertTrue(posts.restoreUpdatedAt() >= 1)
        assertEquals(old.toEpochMilli(), posts.listOwn(id, PostQuery()).items.single { it.key == r.key }.updatedAt)
    }

    @Test
    fun `a root tag is dropped when one of its sub-tags is present on the same post`() {
        val users = UserService(cfg)
        val posts = PostService(cfg)
        val id = newUser(users, "twitch", "t-prune", "Prune")
        val p = posts.create(id, PostInput(title = "P", content = "x", tags = listOf("factorio", "factorio/base", "autre", "a", "a/b/c")))
        fun names(key: String) = posts.listOwn(id, PostQuery()).items.single { it.key == key }.tags.map { it.name }.toSet()
        assertEquals(setOf("factorio/base", "autre", "a/b/c"), names(p.key))
        // Le filtre sur la racine retrouve toujours le post.
        assertEquals(1, posts.listOwn(id, PostQuery(tags = listOf("factorio"))).items.count { it.key == p.key })
        // Rattrapage d'un post déjà pollué (racine posée à la main avant les sous-tags).
        val q = posts.create(id, PostInput(title = "Q", content = "x", tags = listOf("solo")))
        dbBlocking {
            val pid = Posts.selectAll().where { Posts.key eq q.key }.single()[Posts.id].value
            val tid = TagService.getOrCreate(id, "solo/sub")
            PostTags.insert { it[post] = pid; it[tag] = tid }
        }
        assertEquals(setOf("solo", "solo/sub"), names(q.key))
        assertTrue(posts.pruneRedundantTags() >= 1)
        assertEquals(setOf("solo/sub"), names(q.key))
    }

    @Test
    fun `admin can close registrations and delete an account without banning`() {
        val users = UserService(cfg)
        val admin = AdminService(users)
        val existing = newUser(users, "twitch", "t-reg-1", "Existing")
        users.setRegistrationOpen(false)
        assertFalse(users.registrationOpen())
        assertEquals(LoginResult.RegistrationClosed, users.loginOrLink(OAuthProfile("twitch", "t-reg-new", "Newcomer", null), null))
        assertTrue(users.loginOrLink(OAuthProfile("twitch", "t-reg-1", "Existing", null), null) is LoginResult.Ok, "un compte existant peut toujours se connecter")
        users.setRegistrationOpen(true)
        assertTrue(users.loginOrLink(OAuthProfile("twitch", "t-reg-new", "Newcomer", null), null) is LoginResult.Ok)

        PostService(cfg).create(existing, PostInput(title = "à supprimer", content = "x"))
        val adminId = newUser(users, "discord", "d-admin", "Boss")
        assertFailsWith<ApiException> { admin.deleteUser(adminId, adminId) }
        admin.deleteUser(adminId, existing)
        assertEquals(0, dbBlocking { Users.selectAll().where { Users.id eq existing }.count() })
        assertEquals(0, dbBlocking { Posts.selectAll().where { Posts.user eq existing }.count() }, "ses posts disparaissent avec lui")
    }

    @Test
    fun `social links keep a clean name and refuse links that do not match the icon`() {
        val users = UserService(cfg)
        val id = newUser(users, "discord", "d-300", "Linker")
        users.setSocials(id, listOf(SocialDto("fa-brands fa-twitch", "https://twitch.tv/x"), SocialDto("GitHub", "https://github.com/x"), SocialDto("fa-solid fa-link", "https://ftnl.fr")))
        assertEquals(listOf("twitch", "github", "website"), users.me(id).socials.map { it.icon })
        val e = assertFailsWith<ApiException> { users.setSocials(id, listOf(SocialDto("discord", "https://twitch.tv/x"))) }
        assertEquals("invalid_social_url", e.code)
        // Une icône inconnue devient un lien web classique (et doit alors être un lien http(s) valide).
        users.setSocials(id, listOf(SocialDto("fa-brands fa-patreon", "https://patreon.com/moi"), SocialDto("nope", "https://exemple.org")))
        assertEquals(listOf("website", "website"), users.me(id).socials.map { it.icon })
        assertEquals("invalid_social_url", assertFailsWith<ApiException> { users.setSocials(id, listOf(SocialDto("nope", "javascript:alert(1)"))) }.code)
    }

    @Test
    fun `an admin can act as another account, account-level actions stay blocked, and the admin can return`() = testApplication {
        // Cookies de session « Secure » en https : on teste en http pour que le client de test les renvoie.
        application { module(cfg.copy(baseUrl = "http://localhost")) }
        val users = UserService(cfg)
        val target = newUser(users, "twitch", "t-imp", "Target")
        val targetHandle = handleOf(target)
        suspend fun client() = createClient { install(io.ktor.client.plugins.cookies.HttpCookies); followRedirects = false }
        suspend fun HttpResponse.obj() = Json.parseToJsonElement(bodyAsText()).jsonObject
        fun JsonObject.handle() = this["handle"]!!.jsonPrimitive.content

        val admin = client()
        admin.get("/login/dev?name=boss")
        val adminMe = admin.get("/api/me").obj()
        assertEquals("admin", adminMe["role"]!!.jsonPrimitive.content)
        val adminHandle = adminMe.handle()

        // Un utilisateur ordinaire ne peut pas.
        val plain = client()
        plain.get("/login/dev?name=plainuser")
        assertEquals(HttpStatusCode.Forbidden, plain.post("/api/admin/impersonate/$target").status)

        // L'admin agit en tant que la cible.
        assertEquals(HttpStatusCode.NoContent, admin.post("/api/admin/impersonate/$target").status)
        val me = admin.get("/api/me").obj()
        assertEquals(targetHandle, me.handle())
        assertEquals(adminHandle, me["impersonatedBy"]!!.jsonObject.handle())

        // Le contenu est modifiable au nom de la cible (dossiers, tags, posts).
        val folder = admin.post("/api/me/folders") { contentType(ContentType.Application.Json); setBody("""{"name":"Rangé par l'admin"}""") }
        assertEquals(HttpStatusCode.Created, folder.status)
        assertEquals(listOf("Rangé par l'admin"), FolderService.list(target).map { it.name })
        assertEquals(HttpStatusCode.Created, admin.post("/api/me/tags") { contentType(ContentType.Application.Json); setBody("""{"name":"trié"}""") }.status)
        assertEquals(listOf("trié"), TagService.list(target).map { it.name })

        // Les actions sur le compte lui-même sont interdites, ainsi que l'administration.
        for ((method, path) in listOf("DELETE" to "/api/me", "GET" to "/api/me/tokens", "PATCH" to "/api/me", "DELETE" to "/api/me/accounts/twitch", "GET" to "/api/admin/stats")) {
            val r = admin.request(path) { this.method = HttpMethod.parse(method); contentType(ContentType.Application.Json); if (method == "PATCH") setBody("{}") }
            assertEquals(HttpStatusCode.Forbidden, r.status, "$method $path")
        }
        assertEquals(1, dbBlocking { Users.selectAll().where { Users.id eq target }.count() }.toInt(), "le compte n'a pas été supprimé")

        // Retour au compte administrateur.
        assertEquals(HttpStatusCode.NoContent, admin.post("/api/admin/impersonate/stop").status)
        val back = admin.get("/api/me").obj()
        assertEquals(adminHandle, back.handle())
        assertTrue(back["impersonatedBy"].let { it == null || it is JsonNull })

        // Cibles refusées : soi-même, compte banni.
        assertEquals(HttpStatusCode.BadRequest, admin.post("/api/admin/impersonate/${adminMe["id"]!!.jsonPrimitive.long}").status)
        val banned = newUser(users, "twitch", "t-banned", "Banned")
        dbBlocking { Users.update({ Users.id eq banned }) { it[Users.banned] = true } }
        assertEquals(HttpStatusCode.BadRequest, admin.post("/api/admin/impersonate/$banned").status)
    }

    @Test
    fun `profile embed only has twitch, youtube, github and discord buttons`() = testApplication {
        application { module(cfg) }
        val users = UserService(cfg)
        val id = newUser(users, "discord", "d-400", "EmbedUser")
        users.updateMe(id, MePatch(bio = "Ma bio"))
        users.setSocials(
            id, listOf(
                SocialDto("twitch", "https://twitch.tv/x"), SocialDto("youtube", "https://youtube.com/@x"), SocialDto("x", "https://x.com/x"),
                SocialDto("github", "https://github.com/x"), SocialDto("discord", "https://discord.gg/abc"), SocialDto("spotify", "https://open.spotify.com/user/1"),
            )
        )
        PostService(cfg).create(id, PostInput(title = "Un post", content = "x", visibility = "public"))

        val html = client.get("/u/embeduser").bodyAsText()
        Assumptions.assumeFalse(html.contains("Frontend non compilé"), "web/dist absent")
        val json = Regex("""<script id="discord:component-embed"[^>]*>(.*?)</script>""").find(html)!!.groupValues[1]
        assertTrue(json.toByteArray().size <= 3000)
        assertEquals(17, Json.parseToJsonElement(json).jsonObject["component"]!!.jsonObject["type"]!!.jsonPrimitive.int)
        val labels = Regex(""""label":"([^"]+)"""").findAll(json).map { it.groupValues[1] }.toList()
        assertEquals(listOf("Voir le profil", "Twitch", "YouTube", "GitHub", "Discord"), labels)
        assertTrue(json.contains("1 post public"))
        assertTrue(json.contains("Ma bio"))
    }

    @Test
    fun `old handle redirects permanently and post embed never shows hidden content`() = testApplication {
        application { module(cfg) }
        val client = createClient { followRedirects = false }
        val users = UserService(cfg)
        val id = newUser(users, "discord", "d-500", "Before")
        users.updateMe(id, MePatch(handle = "after"))
        val r = client.get("/u/before")
        assertEquals(HttpStatusCode.MovedPermanently, r.status)
        assertEquals("/u/after", r.headers[HttpHeaders.Location])
        // Le chemin de dossier est conservé (et encodé) dans la redirection.
        assertEquals("/u/after/Mes%20Plans/Trains", client.get("/u/before/Mes%20Plans/Trains").headers[HttpHeaders.Location])

        val posts = PostService(cfg)
        val p = posts.create(id, PostInput(title = "Secret", content = "contenu confidentiel", visibility = "public", password = "pw"))
        val html = client.get("/${p.key}").bodyAsText()
        Assumptions.assumeFalse(html.contains("Frontend non compilé"), "web/dist absent")
        assertFalse(html.contains("masqué"))
        assertFalse(html.contains("contenu confidentiel"))
        val og = client.get("/og/${p.key}.png")
        assertEquals(ContentType.Image.PNG, og.contentType())
        assertNotNull(og.bodyAsBytes())
    }
}
