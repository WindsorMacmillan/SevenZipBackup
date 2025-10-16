package windsor.sevenzipbackup.uploaders;

import windsor.sevenzipbackup.UploadThread;
import windsor.sevenzipbackup.uploaders.Authenticator.AuthenticationProvider;
import windsor.sevenzipbackup.uploaders.webdav.NextcloudUploader;

public abstract class Uploader {
    private String name;
    private String id;
    private boolean authenticated;
    private boolean errorOccurred;
    private AuthenticationProvider authProvider;
    protected UploadThread.UploadLogger logger;
    
    protected Uploader(String name, String id) {
        this.name = name;
        this.id = id;
    }
    
    public String getName() {
        return name;
    }
    protected void setName() {
        this.name = NextcloudUploader.UPLOADER_NAME;
    }
    public String getId() {
        return id;
    }
    protected void setId(String id) {
        this.id = id;
    }
    public AuthenticationProvider getAuthProvider() {
        return authProvider;
    }
    protected void setAuthProvider(AuthenticationProvider authProvider) {
        this.authProvider = authProvider;
    }
    public boolean isAuthenticated() {
        return authenticated;
    }
    protected void setAuthenticated() {
        this.authenticated = true;
    }
    public boolean isErrorWhileUploading() {
        return errorOccurred;
    }
    protected void setErrorOccurred() {
        this.errorOccurred = true;
    }
    public abstract void test(java.io.File testFile);
    public abstract void uploadFile(java.io.File file, String type);
    public abstract void close();
}
