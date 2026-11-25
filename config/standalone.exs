# Akkoma configuration for running hugmesh-akkoma ON ITS OWN, without the rest of HugMesh
# (no Casdoor, no gateways, no Kubernetes). Everything instance-specific is read from
# the environment, so the same file serves compose/docker-compose.yml and any other
# container runner. Mount it at /etc/akkoma/config.exs with mode 0440 (the release
# refuses a config file that is world-readable or group-writable) and run the pod or
# container with group 1000.
#
# Required environment:
#   AKKOMA_HOST        the public host name, e.g. social.example.org
#   SECRET_KEY_BASE    64+ random characters      (openssl rand -base64 48)
#   SIGNING_SALT       8+ random characters       (openssl rand -base64 8)
#   DB_HOST DB_PASSWORD   PostgreSQL (user and database both "akkoma" unless DB_USER / DB_NAME are set)
# Optional:
#   AKKOMA_SCHEME      https (default) or http, for a local trial
#   AKKOMA_PORT        public port in the URL (default 443 for https, 80 for http)
#   AKKOMA_NAME, AKKOMA_EMAIL      instance name and contact address
#   AKKOMA_REGISTRATIONS_OPEN      "true" to allow open sign-up (default: closed)
#   AKKOMA_FEDERATING              "false" to turn federation off (a private or onion-only instance); default true
#   AKKOMA_SMTP_RELAY (+ _PORT _USER _PASSWORD _TLS _FROM)   outbound mail; unset = mail disabled
import Config

scheme = System.get_env("AKKOMA_SCHEME", "https")
host = System.get_env("AKKOMA_HOST") || raise "AKKOMA_HOST is not set"
port = String.to_integer(System.get_env("AKKOMA_PORT") || (if scheme == "https", do: "443", else: "80"))

config :pleroma, Pleroma.Web.Endpoint,
  url: [host: host, scheme: scheme, port: port],
  http: [ip: {0, 0, 0, 0}, port: 4000],
  secret_key_base: System.get_env("SECRET_KEY_BASE"),
  signing_salt: System.get_env("SIGNING_SALT")

config :pleroma, :instance,
  name: System.get_env("AKKOMA_NAME", "Akkoma"),
  email: System.get_env("AKKOMA_EMAIL", "admin@#{host}"),
  notify_email: System.get_env("AKKOMA_SMTP_FROM", "noreply@#{host}"),
  registrations_open: System.get_env("AKKOMA_REGISTRATIONS_OPEN", "false") == "true",
  federating: System.get_env("AKKOMA_FEDERATING", "true") == "true"

# `pleroma start` refuses to run without an uploads base_url. Uploads are served from
# the instance's own host; see README.md, "Media host", for the trade-off.
config :pleroma, Pleroma.Upload, base_url: "#{scheme}://#{host}#{if port in [80, 443], do: "", else: ":#{port}"}/media/"

config :pleroma, Pleroma.Repo,
  adapter: Ecto.Adapters.Postgres,
  username: System.get_env("DB_USER", "akkoma"),
  password: System.get_env("DB_PASSWORD"),
  database: System.get_env("DB_NAME", "akkoma"),
  hostname: System.get_env("DB_HOST")

config :pleroma, Pleroma.Mailer,
  enabled: System.get_env("AKKOMA_SMTP_RELAY", "") != "",
  adapter: Swoosh.Adapters.SMTP,
  relay: System.get_env("AKKOMA_SMTP_RELAY"),
  port: String.to_integer(System.get_env("AKKOMA_SMTP_PORT") || "587"),
  username: System.get_env("AKKOMA_SMTP_USER"),
  password: System.get_env("AKKOMA_SMTP_PASSWORD"),
  tls: String.to_atom(System.get_env("AKKOMA_SMTP_TLS") || "if_available"),
  auth: :always,
  retries: 2
