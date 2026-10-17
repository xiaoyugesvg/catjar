官方 JAR 镜像（2026-10-17 下载，仅作备用/校验）
================================================

来源：FongMi/CatVodSpider（main 分支）
  https://raw.githubusercontent.com/FongMi/CatVodSpider/main/app/libs/catvod-api.jar
  https://raw.githubusercontent.com/FongMi/CatVodSpider/main/jar/custom_spider.jar
  https://raw.githubusercontent.com/FongMi/CatVodSpider/main/jar/custom_spider.jar.md5

文件：
  catvod-api.jar         127675 字节
    SHA256 31EF43369FDFE43DECE9E0149965DED78ABC758EEFE1FE675607115E0E96AA29
    说明：class jar，class 文件版本 65 → 需要 Java 21 运行时。

  custom_spider.jar      142512 字节
    SHA256 F19AFC0F7D0E8D3C470E18729102AF932D38C06E139098AA9B40A7D1B9871E30
    MD5    48589d6ba75769589ae764aef5c3d885
    说明：**Android dex jar**（内含 classes.dex），只能给 TVBox/FongMi 的
          DexClassLoader 使用。

⚠️ 重要：JVM 网关不能直接加载 custom_spider.jar
------------------------------------------------
这个官方预编译 jar 是 dex 格式，标准 JVM 不认识 classes.dex。
用 dex2jar 转换后虽然能生成 .class，但 R8 优化过的类会丢失/改错继承关系，
运行时会报：
  VerifyError: Expecting a stackmap frame
  AbstractMethodError: Receiver class java.lang.Object ...

因此 JVM 网关需要的是**非 dex 的 .class jar**：
  1) 用 FongMi/CatVodSpider 源码编译（关闭 minify / 取 javac 中间产物）后自己打包；
  2) 或者改用 CatVodSpider 的 Python / QuickJS 源（不需要 JVM）。

这里的 catvod-api.jar 可以直接给网关用；custom_spider.jar 仅作 TVBox 用途的镜像备份。
