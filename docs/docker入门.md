# Docker 入门完全指南：从零上手，理论与实战全覆盖

---

## 一、Docker 是什么？

**Docker** 是一个开源的**容器化（Containerization）平台**，由 Go 语言编写。它的核心思想是：将应用程序及其所有依赖项打包成一个独立的、可移植的**镜像（Image）**，然后通过这个镜像创建**容器（Container）**来运行应用。

想象一下：你开发了一个项目在本机运行正常，但部署到服务器上却问题百出——JDK 版本不对、Redis 依赖缺失、环境变量冲突……传统方式是逐台机器配置环境，费时费力。Docker 的出现彻底解决了这个痛点：**一次打包，处处运行**。

### 1.1 Docker 历史

- **2010 年**：dotCloud 公司成立，做 PaaS 云计算服务，基于 LXC（Linux Container）研发了容器技术，并将其命名为 **Docker**。
- **2013 年**：Docker 开源，迅速在社区传播开来。
- **2014 年 4 月**：Docker 1.0 正式发布，从此 Docker 每月更新，一路火到现在。

### 1.2 Docker 能做什么？

| 场景 | 传统方式 | 使用 Docker 后 |
|---|---|---|
| 交付部署 | 每台机器手动配环境、装依赖 | 打包成镜像，一键拉起运行 |
| 服务扩展 | 逐台登录服务器操作 | 通过镜像快速横向扩容 |
| 环境一致 | 开发/测试/生产环境差异大 | 镜像保证各处环境高度一致 |
| 资源利用 | 虚拟机笨重，占用资源多 | 容器共享宿主机内核，轻量高效 |

---

## 二、Docker 与虚拟机：有什么区别？

在容器技术出现之前，**虚拟机（VM）**是主流的虚拟化方案。

| 特性 | 虚拟机（VM） | Docker 容器 |
|---|---|---|
| 虚拟化层次 | 虚拟完整硬件 + 独立 Guest OS | 直接复用宿主机内核 |
| 启动速度 | 分钟级（启动整个操作系统） | 秒级（启动进程） |
| 资源占用 | 大（每个 VM 独占一套 OS） | 小（容器共享内核，按需分配） |
| 隔离性 | 完全隔离，安全性高 | 进程级隔离，轻量但也足够安全 |
| 体积 | 通常数 GB | 通常几十到几百 MB |

**为什么 Docker 比虚拟机快？**

虚拟机需要加载一个完整的 Guest OS（分钟级），而 Docker 直接复用宿主机的内核，无需启动操作系统，因此是**秒级启动**，效率高得多。

---

## 三、Docker 核心概念

理解 Docker 的三个核心概念：镜像、容器、仓库。

### 3.1 镜像（Image）

**镜像**相当于一个只读的模板。你可以通过一个镜像创建多个容器服务。

- 类比：**面向对象中的类（Class）**
- 镜像可以层层叠加（基于 UnionFS 联合文件系统）
- 示例：`nginx` 镜像 → 创建 → `nginx01` 容器

### 3.2 容器（Container）

**容器**是镜像的运行实例。一个容器就是一个简易的 Linux 系统。

- 类比：**面向对象中的实例对象（Object）**
- 容器是相互隔离的，每个容器有自己独立的文件系统
- 对容器的一切操作（启停删改）不影响镜像本身

### 3.3 仓库（Repository）

**仓库**是存放镜像的地方。

- **公有仓库**：Docker Hub（官方）、阿里云私有仓库
- **私有仓库**：自己搭建的 Harbor、阿里云容器镜像服务等

---

## 四、Windows 上安装 Docker Desktop

> 本节面向 Windows 10/11 用户，详细介绍 Docker Desktop 的完整安装流程。

### 4.1 系统要求

- Windows 10 21H2 及以上版本 / Windows 11
- CPU 至少 2 核 4G 内存，推荐 8G 以上
- **必须开启 CPU 虚拟化**

### 4.2 第一步：开启 CPU 虚拟化

这一步非常关键，不开启虚拟化 Docker 根本跑不起来。

1. 按 `Ctrl + Shift + Esc` 打开任务管理器
2. 切换到【性能】→【CPU】，查看右侧是否显示 **虚拟化：已启用**

如果显示"已禁用"，需要重启电脑，开机时按品牌对应快捷键进入 BIOS 开启 VT 虚拟化：

| 品牌 | 进入 BIOS 快捷键 |
|---|---|
| 联想 | F12 / F2 |
| 惠普 | F10 / Esc |
| 戴尔 | F2 |
| 华硕 / 微星 | Del / F2 |
| 华为 / 荣耀 | F2 |

进入 BIOS 后找到「VT-x」「Virtualization Technology」「SVM Mode」选项，设置为 **Enabled**，保存重启。

### 4.3 第二步：开启 WSL2

Docker Desktop 在 Windows 上默认使用 **WSL2（Windows Subsystem for Linux 2）** 作为底层引擎，速度比 Hyper-V 快 30% 以上。

以**管理员身份**打开 PowerShell，执行：

```powershell
wsl --install
```

命令执行完后会提示重启电脑。重启后 WSL2 就安装好了。

### 4.4 第三步：下载安装 Docker Desktop

下载地址：

- **官方地址**：https://www.docker.com/products/docker-desktop/
- **国内加速**（推荐）：使用阿里云镜像站下载，速度更快

安装时请注意：

- ✅ 勾选 **Use WSL 2 instead of Hyper-V**（必选，Win11 家庭版没有 Hyper-V）
- ❌ 不要勾选「使用 Windows 容器」

### 4.5 第四步：配置国内镜像加速

Docker 官方镜像源在国外，国内下载镜像经常几 KB/s 甚至失败。必须配置国内镜像源。

1. 打开 Docker Desktop → 点击右上角【设置】（齿轮图标）
2. 选择【Docker 引擎】
3. 在配置文件中添加以下内容：

```json
{
  "builder": {
    "gc": {
      "defaultKeepStorage": "20GB",
      "enabled": true
    }
  },
  "experimental": false,
  "registry-mirrors": [
    "https://docker.mirrors.ustc.edu.cn",
    "https://hub-mirror.c.163.com",
    "https://mirror.baidubce.com",
    "https://你的阿里云加速地址.mirror.aliyuncs.com"
  ]
}
```

> 阿里云加速器获取方式：登录 https://cr.console.aliyun.com/cn-hangzhou/instances/mirrors 即可免费获取专属加速地址。

4. 点击【应用并重启】，重启后执行 `docker info`，确认 `Registry Mirrors` 部分已显示你的镜像源地址。

### 4.6 第五步：验证安装成功

打开 PowerShell，执行：

```bash
docker version
docker run hello-world
```

如果看到 `Hello from Docker!` 的欢迎信息，说明 Docker 安装成功。

---

## 五、Docker 基本命令详解

### 5.1 帮助命令

```bash
docker version           # 显示 Docker 版本信息
docker info              # 显示 Docker 系统信息（镜像数量、容器数量等）
docker 命令 --help       # 查看任意命令的帮助文档
```

### 5.2 镜像命令

```bash
# 查看本地所有镜像
docker images

# 参数说明：
#   REPOSITORY  镜像仓库源
#   TAG         镜像标签（版本号）
#   IMAGE ID    镜像唯一标识
#   CREATED     创建时间
#   SIZE        镜像大小

docker images -a        # 显示所有镜像（含中间层）
docker images -q        # 仅显示镜像 ID

# 搜索镜像
docker search nginx
docker search --filter=STARS=3000 nginx   # 搜索 stars > 3000 的镜像

# 下载镜像
docker pull nginx              # 默认下载 latest 版本
docker pull nginx:5.7         # 下载指定版本

# 删除镜像
docker rmi nginx               # 删除指定镜像
docker rmi -f 镜像ID           # 强制删除
docker rmi $(docker images -q) # 删除所有镜像
```

### 5.3 容器命令

> **注意**：必须先有镜像才能创建容器。

#### 新建并启动容器

```bash
docker run [可选参数] 镜像名

# 参数说明：
#   --name="Name"   容器名称
#   -d              后台运行（detached）
#   -it             交互式运行（进入容器）
#   -p 宿主机端口:容器端口   端口映射
#   -P              随机端口映射

# 示例：启动并进入 CentOS 容器
docker run -it centos /bin/bash

# 示例：后台运行 Nginx，端口映射 3344:80
docker run -d --name nginx01 -p 3344:80 nginx
```

#### 列出容器

```bash
docker ps               # 列出当前运行的容器
docker ps -a            # 列出所有容器（含已停止的）
docker ps -a -n=1       # 显示最近创建的 1 个容器
docker ps -q            # 仅显示容器 ID
```

#### 退出容器

```bash
exit               # 退出并停止容器
Ctrl + P + Q       # 退出容器但不停止（后台继续运行）
```

#### 启停容器

```bash
docker start 容器ID/容器名      # 启动已停止的容器
docker restart 容器ID/容器名     # 重启容器
docker stop 容器ID/容器名        # 停止容器
docker kill 容器ID/容器名        # 强制停止容器
```

#### 删除容器

```bash
docker rm 容器ID               # 删除容器（不能删除运行中的）
docker rm -f 容器ID            # 强制删除
docker rm -f $(docker ps -aq)  # 删除所有容器
```

### 5.4 常用进阶命令

```bash
# 后台启动容器（注意：必须保持前台有进程，否则容器会自动停止）
docker run -d centos /bin/sh -c "while true;do echo hello;sleep 1;done"

# 查看容器日志
docker logs -tf --tail 10 容器ID
# 参数：
#   -tf     显示日志
#   --tail  显示最近 N 条日志

# 查看容器内进程
docker top 容器ID

# 查看容器元数据
docker inspect 容器ID

# 进入正在运行的容器（开启新终端）
docker exec -it 容器ID /bin/bash

# 进入正在运行的容器（进入当前终端）
docker attach 容器ID

# 从容器拷贝文件到主机
docker cp 容器ID:/容器内路径 /主机路径
```

### 5.5 常用命令速查表

| 命令 | 说明 |
|---|---|
| `docker run -d --name xxx -p 8080:80 nginx` | 后台运行 Nginx |
| `docker ps` | 查看运行中的容器 |
| `docker ps -a` | 查看所有容器 |
| `docker stop xxx` | 停止容器 |
| `docker rm xxx` | 删除容器 |
| `docker exec -it xxx /bin/bash` | 进入容器 |
| `docker logs -f xxx` | 查看日志 |
| `docker images` | 查看本地镜像 |
| `docker rmi xxx` | 删除镜像 |
| `docker pull xxx:tag` | 拉取镜像 |

---

## 六、实战：使用 Docker 部署 Nginx

### 6.1 搜索并下载镜像

```bash
docker search nginx
docker pull nginx
```

### 6.2 启动容器

```bash
docker run -d --name my-nginx -p 8080:80 nginx
```

参数说明：

- `-d`：后台运行
- `--name my-nginx`：容器命名为 my-nginx
- `-p 8080:80`：将宿主机的 8080 端口映射到容器的 80 端口

### 6.3 验证运行

```bash
# 查看容器状态
docker ps

# 访问测试
curl localhost:8080
# 如果看到 Nginx 欢迎页面 HTML，说明部署成功
```

### 6.4 访问容器内部

```bash
docker exec -it my-nginx /bin/bash
```

---

## 七、Docker 镜像原理：分层存储

### 7.1 UnionFS（联合文件系统）

Docker 镜像在下载时可以看到是一层一层下载的，这就是 **UnionFS** 的特性。

```
bootfs（boot file system）
├── bootloader（引导加载器）
└── kernel（内核）

rootfs（root file system）
├── /dev, /proc, /bin, /etc ...
└── Ubuntu / CentOS 等发行版
```

**为什么 CentOS 在 Docker 中只有 200MB 左右？**

因为 Docker 复用宿主机的内核，不需要在每个容器里装一套完整的 OS。rootfs 只需要包含最基本的目录和工具即可，而 bootfs 所有容器共享宿主机的内核。

### 7.2 分层下载的好处

- **资源共享**：多个镜像共用相同的基础层，磁盘和内存占用大大减少
- **增量更新**：修改只会在当前层增加新内容，不影响基础镜像

### 7.3 提交自定义镜像

如果修改了容器内容，可以将其提交为新镜像：

```bash
docker commit -m="提交描述" -a="作者" 容器ID 目标镜像名:TAG
```

> 类似于 Git 的 commit 操作，tag 就是版本号。

---

## 八、容器数据卷：数据持久化

### 8.1 什么是容器数据卷？

容器运行时的数据存储在容器的文件系统中，但**删除容器后数据会丢失**。容器数据卷（Volume）的作用是：

1. 将容器内的目录**挂载**到宿主机的目录
2. 实现容器数据的**持久化**
3. 多个容器之间可以**共享数据**

### 8.2 使用数据卷（-v）

```bash
# 格式：docker run -v 宿主机目录:容器内目录 镜像名
docker run -it -v /home/myhost:/home/container centos /bin/bash
```

挂载后，两边目录内容**双向同步**：

- 在容器内创建/修改文件 → 宿主机对应目录同步更新
- 在宿主机创建/修改文件 → 容器内对应目录同步更新
- **停止容器后修改宿主机文件** → 重启容器后**数据依然同步**

### 8.3 具名挂载与匿名挂载

```bash
# 匿名挂载（只指定容器内路径）
docker run -d -v /etc/nginx nginx

# 具名挂载（推荐，有意义的名字）
docker run -d -v my-nginx:/etc/nginx nginx

# 指定路径挂载
docker run -d -v /host/path:/container/path nginx
```

通过 `docker volume ls` 可以查看所有卷，具名挂载更方便管理和定位。

### 8.4 实战：部署 MySQL 并持久化数据

```bash
# 拉取 MySQL
docker pull mysql:5.7

# 启动容器，配置数据卷和密码
docker run -d \
  --name mysql01 \
  -p 3306:3306 \
  -v /host/mysql/conf:/etc/mysql/conf.d \
  -v /host/mysql/data:/var/lib/mysql \
  -e MYSQL_ROOT_PASSWORD=123456 \
  mysql:5.7
```

- `-e MYSQL_ROOT_PASSWORD=123456`：设置 MySQL root 密码
- 挂载了配置目录和数据目录，即使删除容器，本地数据依然保留

### 8.5 数据卷容器（容器间共享数据）

```bash
# 创建数据卷容器
docker run -d --name data-container -v /sharedata nginx

# 其他容器从 data-container 继承卷
docker run -d --name app1 --volumes-from data-container nginx
docker run -d --name app2 --volumes-from data-container nginx
```

---

## 九、Dockerfile：构建自定义镜像

### 9.1 什么是 Dockerfile？

Dockerfile 是一个文本文件，里面写的是一条条构建指令（类比 Makefile），用于自动化构建 Docker 镜像。

构建流程：

```
编写 Dockerfile → docker build → 生成镜像 → docker run → 启动容器
```

### 9.2 Dockerfile 常用指令

| 指令 | 说明 |
|---|---|
| `FROM` | 指定基础镜像 |
| `MAINTAINER` | 镜像作者信息 |
| `RUN` | 构建时运行的命令 |
| `COPY` | 将文件拷贝到镜像中 |
| `ADD` | 同 COPY，但支持 URL 和 tar 自动解压 |
| `WORKDIR` | 设置工作目录 |
| `VOLUME` | 声明挂载点 |
| `EXPOSE` | 暴露端口 |
| `ENV` | 设置环境变量 |
| `CMD` | 容器启动时运行的命令（仅最后一个生效，可被覆盖） |
| `ENTRYPOINT` | 容器启动时运行的命令（可追加参数） |
| `ONBUILD` | 触发指令（子镜像构建时执行） |

### 9.3 实战：构建带 Vim 和 Net-tools 的 CentOS 镜像

#### 编写 Dockerfile

```dockerfile
FROM centos:7
MAINTAINER wsp <xxx@qq.com>

ENV MYPATH /usr/local
WORKDIR $MYPATH

RUN yum -y install vim
RUN yum -y install net-tools

EXPOSE 80

CMD echo $MYPATH
CMD echo "-----end-----"
CMD /bin/bash
```

#### 构建镜像

```bash
# -f 指定 Dockerfile 路径，-t 指定镜像名:版本
docker build -f mydockerfile-centos -t mycentos:0.1 .
```

#### 运行测试

```bash
docker run -it mycentos:0.1
# 进入容器后，vim 和 ifconfig 命令都可以使用了
```

### 9.4 CMD 与 ENTRYPOINT 的区别

```dockerfile
# CMD：会被 docker run 时的参数覆盖
FROM centos:7
CMD ["ls", "-a"]

# ENTRYPOINT：docker run 时的参数会追加到命令后面
FROM centos:7
ENTRYPOINT ["ls", "-a"]
```

```bash
# CMD 执行 -l 会报错（-l 替换了整个命令）
docker run cmdtest -l    # ❌ 报错

# ENTRYPOINT 执行 -l 会拼接（ls -a -l）
docker run entrypointtest -l    # ✅ 正常执行
```

---

## 十、Docker 网络

### 10.1 Docker 网络模型

每启动一个容器，Docker 就会给容器分配一个 IP 地址，并自动在容器和宿主机之间建立网络连接。

容器之间通过 **veth-pair**（虚拟网卡对）进行通信，网络转发效率极高。

```bash
# 查看容器网络地址
docker exec 容器ID ip addr
```

### 10.2 网络模式

| 模式 | 说明 |
|---|---|
| `bridge` | 桥接模式（默认），使用 docker0 网桥 |
| `host` | 容器共享宿主机网络 |
| `none` | 不配置网络 |
| `container` | 容器间共享网络命名空间 |

### 10.3 自定义网络

```bash
# 创建自定义网段
docker network create --driver bridge \
  --subnet 192.168.0.0/16 \
  --gateway 192.168.0.1 \
  mynet

# 使用自定义网络启动容器
docker run -d --name container1 --net mynet nginx
docker run -d --name container2 --net mynet nginx

# 在 container1 中可以直接用容器名 ping container2（无需 --link）
docker exec container1 ping container2    # ✅ 可达
```

### 10.4 网络连通

不同网络之间的容器默认无法通信，可以使用 `docker network connect` 连通：

```bash
# 将 container1 连接到 mynet 网络
docker network connect mynet container1

# 连接后 container1 同时拥有两个网络的 IP，可以双向通信
```

---

## 十一、Docker 可视化管理：Portainer

**Portainer** 是一个轻量级的 Docker 图形化管理工具，类似于宝塔面板。

```bash
docker run -d \
  -p 8088:9000 \
  --restart=always \
  -v /var/run/docker.sock:/var/run/docker.sock \
  --privileged=true \
  portainer/portainer
```

安装后访问 `http://localhost:8088`，选择 **Local** 即可进入管理界面。

---

## 十二、常见问题速查

### Q1：Docker 启动报错 / 闪退

```powershell
# 检查虚拟化是否开启
wsl --update          # 升级 WSL 内核
wsl --list --verbose  # 查看 WSL 状态
```

### Q2：镜像拉取慢 / connection reset

- 确认已配置国内镜像源（见 4.5 节）
- 关闭系统代理，Docker 不走系统代理

### Q3：端口被占用

```powershell
netstat -ano | findstr "8080"    # 查找占用 8080 端口的进程
```

### Q4：Docker 占用内存过高

在 `C:\Users\你的用户名\.wslconfig` 中添加限制：

```ini
[wsl2]
memory=4GB
processors=2
swap=2GB
```

保存后执行 `wsl --shutdown` 重启 WSL 即可生效。

---

## 十三、总结

通过本文，你应该已经掌握了：

- Docker 的核心概念（镜像、容器、仓库）
- Docker Desktop 在 Windows 上的完整安装与配置
- Docker 常用命令（镜像管理、容器管理、数据卷、网络）
- Dockerfile 编写与自定义镜像构建
- Docker 网络原理与自定义网络
- 数据卷与数据持久化

Docker 是现代软件开发和 DevOps 不可或缺的技术，掌握它可以让你在项目中实现**一次构建、多处运行**，彻底告别"在我机器上能跑"的困境。

祝学习愉快！

---

## 参考资料

- [Docker 官方文档](https://docs.docker.com/desktop/install/windows-install/)
- [微软 WSL2 官方安装指南](https://learn.microsoft.com/zh-cn/windows/wsl/install)
