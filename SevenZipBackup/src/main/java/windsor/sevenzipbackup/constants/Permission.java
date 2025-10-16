package windsor.sevenzipbackup.constants;

public enum Permission {
    
    BACKUP("7zbackup.backup"),
    GET_BACKUP_STATUS("7zbackup.getBackupStatus"),
    GET_NEXT_BACKUP("7zbackup.getNextBackup"),
    RELOAD_CONFIG("7zbackup.reloadConfig"),
    LINK_ACCOUNTS("7zbackup.linkAccounts");
    
    private final String permission;
    
    Permission(String permission) {
        this.permission = permission;
    }
    
    public String getPermission() {
        return permission;
    }
    
    @Override
    public String toString() {
        return permission;
    }
}
