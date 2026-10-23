pluginManagement {
    repositories {
        // 权威源优先：CI 跑在境外 runner，阿里云镜像偶发超时/5xx 会让 Gradle 直接中止插件解析
        // （仓库返回非 404 错误时不会 fallback 到下一个仓库），故官方源打头、阿里云镜像降为兜底
        google()
        gradlePluginPortal()
        mavenCentral()
        // 阿里云镜像兜底：本机直连 Maven Central / Google 不通时仍可用
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 权威源优先：CI 跑在境外 runner，阿里云镜像偶发 5xx 会让 Gradle 直接中止依赖解析
        // （仓库返回非 404 错误时不会 fallback 到下一个仓库），故官方源打头、阿里云镜像降为兜底
        google()
        mavenCentral()
        // 阿里云镜像兜底：本机（国内网络）直连 Maven Central / Google 不通时仍可用
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://jitpack.io") }
        // GeckoView（Mozilla 开源浏览器引擎）官方仓库
        maven { url = uri("https://maven.mozilla.org/maven2/") }
    }
}

rootProject.name = "Anise Write"
include(":app", ":aidl-aci-browser", ":aidl-aci-core", ":mnn", ":llama", ":lib_aci", ":cap_main", ":xposed-stub", ":aci-app", ":terminal-core", ":genuiagent-sdk", ":miniapp-sdk", ":kaleidobox", ":plugin-contract", ":plugin-engine", ":plugin-express", ":plugin-devkit", ":plugin-units", ":plugin-todo", ":plugin-sysinfo", ":plugin-zorvweb", ":plugin-signcheck", ":plugin-zxwidget",
    // CodeCanvas 出图 SDK（脚本驱动多引擎多后端，纯端侧渲染，不依赖任何服务器）
    // 依赖方向：app → renderer-compose / renderer-canvas / renderer-svg / engine-kotlindsl / llm-connector → codecanvas-core
    ":codecanvas-core", ":engine-kotlindsl", ":renderer-canvas", ":renderer-compose", ":renderer-svg", ":llm-connector")
project(":mnn").projectDir = file("llm/mnn")
project(":llama").projectDir = file("llm/llama")
// CodeCanvas 子模块实际位于 codecanvas/<模块名>/，模块名与目录名不同名，必须显式指路
for (cc in listOf("codecanvas-core", "engine-kotlindsl",
    "renderer-canvas", "renderer-compose", "renderer-svg", "llm-connector")) {
    project(":$cc").projectDir = file("codecanvas/$cc")
}
