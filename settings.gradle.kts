rootProject.name = "hompage-comments"

// Monorepo: every deployable service lives under apps/. Adding one is a new
// folder plus a line here — nothing else moves.
include("apps:comment-api")
include("apps:resume-api")
