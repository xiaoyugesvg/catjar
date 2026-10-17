把两个 jar 放在本目录：

  catvod-api.jar
    FongMi/CatVodSpider 仓库的 app/libs/catvod-api.jar
    （class 文件版本 65 → 需要 Java 21；可直接用）

  custom_spider.jar
    必须是**非 dex** 的 JVM class jar。
    官方 jar/custom_spider.jar 解压后只有 classes.dex，是给 Android
    DexClassLoader 用的，标准 JVM 不能加载（dex2jar 转换后也会因 R8
    优化报 VerifyError / AbstractMethodError）。

    获取方式：
      1) 用 FongMi/CatVodSpider 源码编译，取 R8 之前的 javac 中间产物打包；
      2) 或改用 CatVodSpider 的 Python / QuickJS 源（不需要 JVM）。

    详见仓库根目录 read.md 的「关于 custom_spider.jar」。

如果 custom_spider.jar 里没有打包 okhttp / gson / slf4j 等依赖，
把它们放到本目录的 lib/ 子目录（入口脚本会把 lib/* 加进类路径）。
