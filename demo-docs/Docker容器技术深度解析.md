# Docker 容器技术深度解析

## 容器与虚拟化

容器是一种操作系统级别的虚拟化技术，允许多个隔离的用户空间实例共享同一个操作系统内核。与传统虚拟机不同，容器不需要完整的 Guest OS，因此启动速度更快（通常在毫秒级），资源开销更小。

传统虚拟化通过 Hypervisor 在物理硬件之上运行多个操作系统实例，每个实例都有独立的内核、驱动和系统库。容器的隔离性依赖于 Linux 内核的 namespace 和 cgroup 机制，所有容器共享宿主机的内核，但拥有独立的进程空间、网络栈、文件系统和用户权限。

## 核心技术

### Namespace — 资源隔离

Linux namespace 是容器隔离的基石。主要包括：
- PID namespace：进程 ID 隔离，容器内进程从 1 开始编号
- NET namespace：网络栈隔离，每个容器拥有独立的网卡、IP、路由表
- MNT namespace：文件系统挂载点隔离
- UTS namespace：主机名和域名隔离
- IPC namespace：进程间通信资源隔离
- USER namespace：用户和组 ID 映射（Docker 默认不启用，但支持配置）
- Cgroup namespace：控制组视图隔离（Linux 4.6+）

### Cgroup — 资源限制

Control Groups 负责限制、记录和隔离进程组的物理资源使用。Docker 通过 cgroup 控制容器的 CPU 配额、内存上限、磁盘 I/O 优先级和网络带宽。例如，通过 --cpus 参数可以限制容器使用的 CPU 核心数，通过 --memory 限制最大内存使用量，超出限制时容器会被 OOM Killer 终止。

### UnionFS — 镜像分层

Docker 镜像采用分层构建的方式，每一层对应 Dockerfile 中的一条指令。这些层通过联合文件系统叠加在一起，形成容器的完整文件系统视图。

常用的存储驱动包括：
- overlay2：当前推荐的驱动，性能好，inode 利用率高
- aufs：Docker 最早使用的驱动，已逐渐被 overlay2 取代
- devicemapper：早期 CentOS/RHEL 的默认驱动，已不推荐
- btrfs/zfs：适合需要快照、压缩等高级存储特性的场景

镜像分层的最大好处是共享和复用。如果多个容器使用相同的基础镜像，这些层只需要存储一份，大大节省磁盘空间和网络传输带宽。

## Dockerfile 最佳实践

编写高效的 Dockerfile 是容器化应用的关键技能。核心原则包括：

### 多阶段构建

将编译环境和运行环境分离。在第一个阶段使用完整的 SDK 编译应用，在第二个阶段只复制编译产物，最终镜像只包含运行时依赖。这样可以减少 90% 以上的镜像体积。

### 层缓存优化

Docker 按顺序构建每一层，如果某一层未变化，则使用缓存。因此应该将变化频率低的指令放在前面（如安装系统依赖），变化频率高的放在后面（如复制源代码）。COPY 和 ADD 指令会计算文件内容的哈希来决定是否使用缓存。

### 最小权限原则

容器默认以 root 运行，存在安全风险。应该通过 USER 指令切换到非特权用户运行应用。同时，避免在 Dockerfile 中硬编码密钥、密码等敏感信息——应通过环境变量、Secret 管理或挂载卷的方式传递。

## 网络模型

Docker 提供多种网络驱动：
- bridge：默认模式，容器连接到 docker0 虚拟网桥，通过 NAT 访问外部网络
- host：容器直接使用宿主机网络栈，性能最优但端口冲突风险高
- overlay：跨主机的容器通信，基于 VXLAN 隧道封装，是 Docker Swarm 的基础
- macvlan：容器获得物理网络中的独立 MAC 地址和 IP，适合传统网络架构迁移
- none：完全隔离，无网络访问

## 数据管理

Docker 提供三种数据持久化方式：
- Volume：由 Docker 管理的存储卷，存储在宿主机的特定目录（/var/lib/docker/volumes/），推荐用于生产环境
- Bind Mount：将宿主机任意路径挂载到容器，适合开发环境和配置文件共享
- tmpfs：内存文件系统，数据不持久化，适合临时文件和敏感信息

## 容器编排

虽然 Docker Compose 可以管理单机上的多容器应用，但生产环境需要编排平台来处理跨主机的调度、服务发现、负载均衡和自动伸缩。

Kubernetes 已成为容器编排的事实标准。K8s 的核心抽象包括 Pod（最小调度单元）、Service（服务发现和负载均衡）、Deployment（声明式部署和滚动更新）、ConfigMap/Secret（配置管理）、Ingress（七层负载均衡）等。

Docker Swarm 是 Docker 原生的编排方案，语法简单、与 Docker CLI 一致性好，但在功能和生态上不如 Kubernetes 丰富。Swarm 模式从 Docker 1.12 起内置于 Docker Engine 中，通过 docker service 命令管理服务。

## 安全考量

容器安全是一个多层防护的问题：使用可信的基础镜像、定期扫描镜像漏洞、运行时启用 seccomp/AppArmor/SELinux 安全配置、限制容器的 capabilities、使用只读根文件系统、配置资源限制防止 DoS 攻击。镜像签名（Docker Content Trust）可确保镜像来源的可信性。

容器逃逸是最严重的安全威胁之一。攻击者如果能够突破容器的隔离，获得宿主机权限，就能控制整个节点。防范措施包括及时更新内核和 Docker 版本、禁用不必要的 capabilities、使用非 root 用户运行容器。
