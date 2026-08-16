<img width="2100" height="900" alt="封面图" src="https://github.com/user-attachments/assets/556e5049-7ffe-4e7d-86ff-cc5656de7543" />

[中文](/README.md)|English

# SevenZipBackup

The very first 7-zip backup plugin for Minecraft server.  

Minecraft world files are numerous and fragmented, making them ideal for solid compression.  
Inspired and reconstructed by [DriveBackupV2](https://github.com/MaxMaeder/DriveBackupV2).  

## What is this? 
Hard drive died? Server hosting provider stopped? Accidentally deleted?  
SevenZipBackup is a plugin that aims to provide an extra layer of security to your data by backing it up remotely.  
Take care of your server data and be well!   

## Features
<img width="699" height="462" alt="7zbackup" src="https://github.com/user-attachments/assets/c6e9846d-b86e-46ef-ba12-622180007163" />  
  
- Saving up to 50% of your drive space using 7z archive with LZMA2!  
- Async backup creation, create multiple backups at the same time!  
- You can also upload backups to Google Drive, OneDrive, Dropbox, (S)FTP server or S3 compatible api.  
- Backup any files or folders for your Minecraft server.  
- Automatically purges backups locally and remotely according to a specified amount.  
- Fully configurable backup interval and custom schedule.  

## Requirements
### General Requirements

- Java 8 or higher
- Java 21 or higher **recommended** for compressing performance!

### Platform Specific Requirements

- Bukkit/Spigot/Paper/Purpur
- Minecraft 1.8 - 1.21.X

## Basic Setup
Download the plugin and copy it to the `plugins` folder on your server.  
Restart your server.  
Edit your `config.yml` in `plugins/SevenZipBackup` folder and reload plugin.  

### Local

Change `local-keep-count` in the `config.yml` to set the number of backups to keep locally. Set to `-1` to keep an unlimited number of backups locally.
Once you have completed the above instructions, backups will run automatically every hour by default.  

### Google Drive
Simply run `/7zbackup linkaccount googledrive` and follow the on-screen instructions.  

### OneDrive
Simply run `/7zbackup linkaccount onedrive` and follow the on-screen instructions.  

### DropBox
Simply run `/7zbackup linkaccount dropbox` and follow the on-screen instructions.  

## Advanced Setup 
<img width="576" height="310" alt="level-time-size" src="https://github.com/user-attachments/assets/3f1e0ab5-2671-437b-b2ec-7eb679e4887e" />



## Privacy Policy
Since we need to access your Google Drive and/or OneDrive data to back up your world, we are required to provide a Privacy Policy.
All the data this plugin uploads and downloads or otherwise accessed from your Google Drive and/or OneDrive stays on your Minecraft server, so we never have access to it.
This plugin physically cannot access any data in your Google Drive and/or OneDrive that is not related to backing up your Minecraft server.
But don't take our word for it, all of this plugin's source code is available here!  
