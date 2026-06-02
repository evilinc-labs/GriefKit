plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "1.21.11"

stonecutter registerChiseled tasks.register("chiseledBuild", stonecutter.chiseled) {
    group = "project"
    ofTask("build")
}

// Build all versions then deploy each JAR to its configured Prism mods directory.
stonecutter registerChiseled tasks.register("chiseledDeploy", stonecutter.chiseled) {
    group = "griefkit"
    ofTask("deploy")
}
