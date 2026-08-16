<img width="2100" height="900" alt="封面图" src="https://github.com/user-attachments/assets/556e5049-7ffe-4e7d-86ff-cc5656de7543" />

# SevenZipBackup

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

- Java 8及以上
- **建议使用**Java 21及以上以带来更好的压缩性能！

### 平台要求

- Bukkit/Spigot/Paper/Purpur
- Minecraft 1.8 - 1.21.X

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
仅需执行 `/7zbackup linkaccount dropbox` 并按照屏幕指示操作即可

## Advanced Setup 高级设置
<img width="576" height="310" alt="level-time-size" src="https://github.com/user-attachments/assets/3f1e0ab5-2671-437b-b2ec-7eb679e4887e" />



## 隐私政策
由于我们需要访问您的Google Drive和OneDrive数据以备份您的服务器存档，因此需要提供隐私政策。
本插件从您的Google Drive和OneDrive访问的所有数据均存储在您的Minecraft服务器上，我们无法访问这些数据。
本插件物理上同样无法访问您Google Drive和OneDrive中任何与备份Minecraft服务器无关的数据。

但您不必仅凭我们的一面之词——本插件全部源代码均在此公开！ 


