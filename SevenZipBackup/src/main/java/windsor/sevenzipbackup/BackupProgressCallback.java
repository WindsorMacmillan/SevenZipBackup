package windsor.sevenzipbackup;

/**
 * 备份进度回调接口
 */
public interface BackupProgressCallback {
    /**
     * 当文件列表准备完成时调用（返回文件数量）
     * @param fileCount 文件数量
     */
    void onFileListPrepared(int fileCount);

    /**
     * 当单个文件处理完成时调用
     */
    void onFileProcessed();

    /**
     * 当整个备份任务完成时调用
     */
    void onBackupComplete();

    /**
     * 当备份出错时调用
     * @param throwable 异常
     */
    void onError(Throwable throwable);
}