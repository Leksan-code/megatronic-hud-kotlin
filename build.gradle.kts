buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // Подключаем официальные стабильные плагины Android и Kotlin
        classpath("com.android.tools.build:gradle:8.4.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.22")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
