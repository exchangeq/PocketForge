# termux.md · Termux 桥接与 SSH 加密

> 目标：让 App 能连上手机里的 Termux，借它的 shell 跑构建、跑脚本、看环境。
> App 不直接起 shell，只走 SSH 客户端 → Termux 自带 sshd（默认 `127.0.0.1:8022`）。

## 1. SSH 用的是什么加密

SSH-2 协议分四层，各层算法在握手时协商：

| 层 | 作用 | 典型算法（现代默认） |
| --- | --- | --- |
| 密钥交换 KEX | 协商会话密钥 | `curve25519-sha256`（首选）、`ecdh-sha2-nistp256` |
| 主机密钥 | 证明「对面真是这台机器」 | `ssh-ed25519`、`rsa-sha2-256` |
| 对称加密 | 传数据 | `chacha20-poly1305@openssh.com`、`aes256-gcm@openssh.com`、`aes128-ctr` |
| 完整性 MAC | 防篡改 | `hmac-sha2-256`（AEAD 套件自带认证，不再单列） |
| 用户认证 | 证明「你是你」 | `publickey`（ed25519）、`password`、`keyboard-interactive` |

要点：
- **非对称只用于握手和认证**，真正传数据用的是协商出来的对称密钥（快）。
- **指纹**是主机公钥的 SHA-256，形如 `SHA256:xxxxxxxx`。App 首次连接记录下来（TOFU），之后指纹变了就拒绝连接 —— 这是防中间人的关键。
- 密码认证的强度取决于 Termux 侧的密码，**私钥认证更安全**；私钥务必带口令（passphrase）。
- 明文 HTTP 只允许局域网调试用；SSH 本身已经加密，不需要再套一层。

## 2. Termux 侧准备

```bash
pkg update && pkg install openssh
sshd                     # 启动，默认监听 8022
whoami                   # 例如 u0_a123，填到 App 的「用户」里
passwd                   # 用密码认证时先设密码
```

免密（推荐）：

```bash
mkdir -p ~/.ssh && chmod 700 ~/.ssh
# 把 App 侧生成的公钥追加进来
cat ~/.ssh/id_ed25519.pub >> ~/.ssh/authorized_keys
chmod 600 ~/.ssh/authorized_keys
```

App 侧填：主机 `127.0.0.1`、端口 `8022`、用户 `u0_a123`、认证方式（密码 / 私钥）。

> 注意：Termux 的 sshd 只在 Termux 进程存活时可用；手机重启后要重新 `sshd`。
> 部分 ROM 会杀后台，需要在系统里给 Termux 加电池白名单。

## 3. 命令红线

即使开了 `allowShell`，下列命令一律拦截，不发给 shell：

- `rm -rf /`、`rm -fr /`、`mkfs*`、`dd if=/dev/zero of=/dev/*`
- `reboot`、`shutdown`、`mount -o remount`、`chmod -R 777 /`
- `su`、`sudo`、`pm uninstall`、`pm clear`、把输出重定向进 `/system`
- fork bomb `:(){ :|:& };:`

工作边界：命令默认 `cd ~`，只在 Termux 自己的目录里操作；要碰外部存储，用 `~/storage` 软链，别直接写 `/storage/emulated/0/Android/data`。

## 4. 排障顺序

1. `termux_status` → 看是连不上、认证失败，还是指纹不匹配；
2. 连不上：Termux 里 `sshd` 起了吗？端口是不是 8022？
3. 认证失败：`whoami` 对不对？私钥换行有没有被截断？密码里有没有空格被吃掉？
4. 指纹不匹配：确实重装过 Termux 才点「信任新指纹」，否则先停手排查。
5. 执行超时：命令挂住了（比如 `top`、交互式 `vim`），换非交互命令或加 `-y`。

## 5. 与技能闸门的关系

`termux_exec` 属于写类工具：**没读完技能就调它，同样会被拒绝**。先 `skills_index` → `skill_read(termux.md)` → 再执行。
