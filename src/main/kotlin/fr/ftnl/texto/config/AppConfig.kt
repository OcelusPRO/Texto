package fr.ftnl.texto.config

/**
 * Configuration de l'application, lue depuis les variables d'environnement.
 * Les noms de la v1 (DB_HOST, DB_USER, DB_PASS, DB_NAME, TEXTO_BASE_URL...) restent acceptés.
 */
data class AppConfig(
    val port: Int,
    val baseUrl: String,
    val siteName: String,
    val dev: Boolean,
    /** Connexion factice /login/dev (développement uniquement). */
    val devLogin: Boolean,
    val db: DbConfig,
    val sessionSignKey: String,
    val sessionEncryptKey: String,
    val discord: OAuthClient?,
    val twitch: OAuthClient?,
    val admins: Set<String>,
    val allowNewUsers: Boolean,
    val maxContentLength: Int,
    val keyLength: Int,
    val legacyPagesDir: String,
    val factorioAssetsDir: String,
    /** URL de l'instance Factorio Blueprint Editor intégrée (service `fbe` du compose), null si absente. */
    val fbeUrl: String?,
    val rateLimitPerMinute: Int,
    val apiRateLimitPerMinute: Int,
) {
    data class DbConfig(val url: String, val user: String?, val password: String?, val poolSize: Int)
    data class OAuthClient(val clientId: String, val clientSecret: String)

    companion object {
        private fun env(vararg names: String): String? =
            names.firstNotNullOfOrNull { System.getenv(it)?.takeIf(String::isNotBlank) }

        fun fromEnv(): AppConfig {
            val dbUrl = env("DATABASE_URL")?.let { if (it.startsWith("jdbc:")) it else "jdbc:postgresql://$it" }
                ?: run {
                    val host = env("DB_HOST") ?: "localhost:5432"
                    val name = env("DB_NAME") ?: "texto"
                    "jdbc:postgresql://$host/$name"
                }
            fun oauth(prefix: String): OAuthClient? {
                val id = env("${prefix}_CLIENT_ID") ?: return null
                val secret = env("${prefix}_CLIENT_SECRET") ?: return null
                return OAuthClient(id, secret)
            }
            return AppConfig(
                port = env("PORT")?.toInt() ?: 8080,
                baseUrl = (env("TEXTO_BASE_URL") ?: "http://localhost:8080").trimEnd('/'),
                siteName = env("TEXTO_SITE_NAME") ?: "Texto",
                dev = env("dev", "TEXTO_DEV") != null,
                devLogin = env("TEXTO_DEV_LOGIN") == "true",
                db = DbConfig(
                    url = dbUrl,
                    user = env("DATABASE_USER", "DB_USER"),
                    password = env("DATABASE_PASSWORD", "DB_PASS"),
                    poolSize = env("DB_POOL")?.toInt() ?: 8,
                ),
                sessionSignKey = env("SESSION_SECRET") ?: "6819b57a326945c1968f45236589",
                sessionEncryptKey = env("SESSION_ENCRYPT") ?: "00112233445566778899aabbccddeeff",
                discord = oauth("DISCORD"),
                twitch = oauth("TWITCH"),
                admins = (env("TEXTO_ADMINS") ?: "").split(',', ' ', ';')
                    .map { it.trim().lowercase() }.filter { it.contains(':') }.toSet(),
                allowNewUsers = env("ALLOW_NEW_USER")?.toBooleanStrictOrNull() ?: true,
                maxContentLength = env("TEXTO_MAX_LENGHT", "TEXTO_MAX_LENGTH")?.toLongOrNull()
                    ?.coerceAtMost(50_000_000)?.toInt() ?: 5_000_000,
                keyLength = env("TEXTO_KEY_LENGHT", "TEXTO_KEY_LENGTH")?.toInt() ?: 10,
                legacyPagesDir = env("TEXTO_LEGACY_PAGES") ?: "/app/pages",
                factorioAssetsDir = env("TEXTO_FACTORIO_ASSETS") ?: "/opt/texto/factorio",
                fbeUrl = env("TEXTO_FBE_URL")?.trimEnd('/'),
                rateLimitPerMinute = env("TEXTO_RATE_USER_LIMITE")?.toInt() ?: 10,
                apiRateLimitPerMinute = env("TEXTO_RATE_APILIMITE")?.toInt() ?: 30,
            )
        }
    }
}
