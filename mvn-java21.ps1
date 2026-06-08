# 设置 Java 21 环境
$env:JAVA_HOME="C:\develop\JDK21"
$env:PATH="C:\develop\JDK21\bin;$env:PATH"

# 执行 Maven 命令
.\mvnw.cmd @args
