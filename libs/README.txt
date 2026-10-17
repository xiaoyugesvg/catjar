把两个 jar 放在本目录：

  catvod-api.jar
    FongMi/TV 仓库根目录执行 ./gradlew apiJar
    产物 build/outputs/api/catvod-api.jar

  custom_spider.jar
    FongMi/CatVodSpider 根目录执行 gradlew spiderJar（Linux: ./gradlew spiderJar）
    产物 jar/custom_spider.jar

如果 custom_spider.jar 里没有打包 okhttp / gson 等依赖，
把它们放到本目录的 lib/ 子目录（entrypoint 会把 lib/* 加进类路径）。
