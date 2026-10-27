<img width="2100" height="900" alt="封面图" src="https://github.com/user-attachments/assets/556e5049-7ffe-4e7d-86ff-cc5656de7543" />

# SevenZipBackup

中文 | [English](/docs/README-English.md)

第一个支持7-zip压缩格式的Minecraft服务器备份插件。 

Minecraft存档文件多且零碎，这使得它们非常适合固实压缩。  
此项目由[DriveBackupV2](https://github.com/MaxMaeder/DriveBackupV2)启发并重构。  

## 这是什么？
硬盘损坏？服务商关停？意外误删？  
7z备份插件能为你的数据提供额外安全和云端的保护。  
数据无价，好好珍惜！  

## 插件功能
<img width="699" height="462" alt="7zbackup" src="https://github.com/user-attachments/assets/c6e9846d-b86e-46ef-ba12-622180007163" />  

- 使用LZMA2算法创建7z压缩文件至多节省50%的硬盘空间！  
- 你同样可以上传备份到Google Drive、OneDrive、Dropbox、(S)FTP服务器或兼容S3的api.  
- 可以为服务器的任意文件/文件夹创建备份.  
- 根据设定上限自动删除最旧的备份文件.  
- 完全自定义的备份间隔和备份计划.  

## 使用需求
### 环境要求

- **Java 21 及以上**（插件字节码目标为 Java 21，低版本 JVM 无法加载）

### 平台要求

- **Paper 1.20.5 及以上**，或基于 Folia 的服务端（如 Luminol）1.20.5 及以上
- 需要服务端内置 Adventure 4.17 及以上（Paper 1.20.5+ 均已满足）

> 为什么不再支持更早版本：插件使用了 Paper/Folia 的区域化调度 API
> （`getAsyncScheduler`、`getGlobalRegionScheduler`、`getRegionScheduler`，
> 用于在 Folia 下正确地把任务投递到对应线程），并对世界保存、BossBar 等操作
> 做了区域线程适配。这些 API 在 1.20 系列才稳定，加上 Java 21 的硬性要求，
> 实际可运行的下限为 1.20.5。
>
> Spigot / Purpur 等非 Paper 系核心不在支持范围内：插件依赖 Paper 特有的调度 API。

## 插件安装
下载插件并放入服务器的`plugins`文件夹  
重启服务器  
编辑`plugins/SevenZipBackup`下的`config.yml`配置文件并重载插件  

### 本地备份

更改`config.yml`中的`local-keep-count`以设定本地备份数量上限。设置为`-1`以不限备份数量  
当你完成上述配置后，插件将会以默认的1小时周期开始备份文件。

### Google Drive
仅需执行 `/7zbackup linkaccount googledrive` 并按照屏幕指示操作即可

### OneDrive
仅需执行 `/7zbackup linkaccount onedrive` 并按照屏幕指示操作即可

### DropBox
Dropbox 不支持设备码授权，且早期的在线授权中转服务已停止运行，因此需要手动获取 `refresh_token`：

1. 打开 <https://www.dropbox.com/developers/apps>，创建或选择你的 App
2. 在 **Permissions** 中勾选 `files.content.write` 与 `files.content.read`，然后 Submit
3. 在 **Settings** 页记下 App key 与 App secret
4. 浏览器访问（替换 `<APP_KEY>`）：
   `https://www.dropbox.com/oauth2/authorize?client_id=<APP_KEY>&token_access_type=offline&response_type=code`
5. 记下页面给出的授权码，换取 refresh_token：
   ```bash
   curl https://api.dropboxapi.com/oauth2/token \
     -d code=<授权码> -d grant_type=authorization_code \
     -d client_id=<APP_KEY> -d client_secret=<APP_SECRET>
   ```
6. 把响应中的 `refresh_token` 填入 `plugins/SevenZipBackup/DropboxCredential.json`：
   ```json
   { "refresh_token": "你的 refresh_token" }
   ```
7. 在 `config.yml` 中填写你自己的 App 凭据，并启用 Dropbox：
   ```yaml
   dropbox:
     enabled: true
     client-id: "你的 App key"
     client-secret: "你的 App secret"
   ```
8. 执行 `/7zbackup reloadconfig`，再用 `/7zbackup test dropbox` 验证连接

> `refresh_token` 与签发它的 App 绑定，**必须**在第 7 步填入同一套 `client-id` / `client-secret`。
> 两项留空时会回退到插件内置凭据，那套凭据属于原作者的 App，仅对通过该 App 签发的令牌有效。

## Advanced Setup 高级设置
<img width="576" height="310" alt="level-time-size" src="https://github.com/user-attachments/assets/3f1e0ab5-2671-437b-b2ec-7eb679e4887e" />



## 隐私政策
由于我们需要访问您的Google Drive和OneDrive数据以备份您的服务器存档，因此需要提供隐私政策。
本插件从您的Google Drive和OneDrive访问的所有数据均存储在您的Minecraft服务器上，我们无法访问这些数据。
本插件物理上同样无法访问您Google Drive和OneDrive中任何与备份Minecraft服务器无关的数据。

但您不必仅凭我们的一面之词——本插件全部源代码均在此公开！ 


