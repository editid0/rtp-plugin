plugins {
    java
}

group = "io.github.editid0"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/releases/") { content { includeGroup("me.clip") } }
    maven("https://maven.enginehub.org/repo/") { content { includeGroupByRegex("com\\.sk89q.*"); includeGroupByRegex("org\\.enginehub.*") } }
    maven("https://jitpack.io") { content { includeGroup("com.github.MilkBowl") } }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.244-stable")

    // Optional integrations (soft-depend; never shaded)
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1") { isTransitive = false }
    compileOnly("me.clip:placeholderapi:2.12.3") { isTransitive = false }
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.21") { isTransitive = false }
    compileOnly("com.sk89q.worldguard:worldguard-core:7.0.21") { isTransitive = false }
    compileOnly("com.sk89q.worldedit:worldedit-core:7.4.6") { isTransitive = false }
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.4.6") { isTransitive = false }

    testImplementation("io.papermc.paper:paper-api:26.3.build.244-stable")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") { expand(props) }
}

tasks.jar {
    archiveFileName = "SwiftRTP-${project.version}.jar"
}
