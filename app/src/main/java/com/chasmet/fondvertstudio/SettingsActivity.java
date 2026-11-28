package com.chasmet.fondvertstudio;

import android.content.SharedPreferences;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.io.File;
import java.util.Date;
import java.util.Locale;

public final class SettingsActivity extends AppCompatActivity {
    public static final String PREFS = "fond_vert_settings";
    public static final String KEY_AUTO_CHECK = "auto_check_updates";
    public static final String KEY_LAST_CHECK = "last_update_check_ms";
    public static final String KEY_CACHED_AVAILABLE = "cached_update_available";
    public static final String KEY_CACHED_VERSION = "cached_update_version";

    private TextView currentVersionText;
    private TextView updateStatusText;
    private TextView availableVersionText;
    private TextView releaseNotesText;
    private TextView progressText;
    private TextView lastCheckText;
    private ProgressBar progressBar;
    private MaterialButton checkButton;
    private MaterialButton downloadButton;
    private SwitchMaterial autoCheckSwitch;

    private AppUpdateManager.UpdateInfo latestInfo;
    private File pendingApk;
    private boolean waitingForInstallPermission;
    private PrompterMcpClient mcp;
    private final Handler mcpUi=new Handler(Looper.getMainLooper());
    private TextView mcpStatus,mcpAddress,mcpBattery;
    private MaterialButton mcpManage,mcpReconnect;
    private final Runnable mcpRefresh=new Runnable(){public void run(){refreshMcp();mcpUi.postDelayed(this,2000);}};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        setupMcp();

        currentVersionText = findViewById(R.id.currentVersionText);
        updateStatusText = findViewById(R.id.updateStatusText);
        availableVersionText = findViewById(R.id.availableVersionText);
        releaseNotesText = findViewById(R.id.releaseNotesText);
        progressText = findViewById(R.id.updateProgressText);
        lastCheckText = findViewById(R.id.lastCheckText);
        progressBar = findViewById(R.id.updateProgressBar);
        checkButton = findViewById(R.id.checkUpdateButton);
        downloadButton = findViewById(R.id.downloadUpdateButton);
        autoCheckSwitch = findViewById(R.id.autoCheckSwitch);
        MaterialButton backButton = findViewById(R.id.settingsBackButton);

        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        currentVersionText.setText("Version installée : " + AppUpdateManager.getCurrentVersionName(this));
        autoCheckSwitch.setChecked(preferences.getBoolean(KEY_AUTO_CHECK, true));
        autoCheckSwitch.setOnCheckedChangeListener((button, checked) ->
                preferences.edit().putBoolean(KEY_AUTO_CHECK, checked).apply());

        backButton.setOnClickListener(v -> finish());
        checkButton.setOnClickListener(v -> checkForUpdate());
        downloadButton.setOnClickListener(v -> startDownload());

        updateLastCheckLabel(preferences.getLong(KEY_LAST_CHECK, 0L));
        if (preferences.getBoolean(KEY_CACHED_AVAILABLE, false)) {
            String cachedVersion = preferences.getString(KEY_CACHED_VERSION, "");
            if (!TextUtils.isEmpty(cachedVersion)) {
                updateStatusText.setText("Une mise à jour a été détectée récemment.");
                availableVersionText.setText("Version disponible : " + cachedVersion);
            }
        }

        checkForUpdate();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if(mcp!=null){mcpUi.removeCallbacks(mcpRefresh);mcpUi.post(mcpRefresh);}
        if (waitingForInstallPermission && pendingApk != null) {
            waitingForInstallPermission = false;
            updateStatusText.setText("Vérification de l'autorisation d'installation…");
            boolean installationOpened = AppUpdateManager.installDownloadedApk(this, pendingApk);
            if (!installationOpened) {
                waitingForInstallPermission = true;
                updateStatusText.setText(
                        "L'autorisation d'installation est nécessaire pour appliquer la mise à jour.");
            }
        }
    }

    @Override protected void onPause(){mcpUi.removeCallbacks(mcpRefresh);super.onPause();}
    private void setupMcp(){
        mcp=PrompterMcpService.client(this);
        mcpStatus=findViewById(R.id.mcpConnectionStatus);mcpAddress=findViewById(R.id.mcpServerAddress);mcpBattery=findViewById(R.id.mcpBatteryStatus);
        mcpManage=findViewById(R.id.mcpManageButton);mcpReconnect=findViewById(R.id.mcpReconnectButton);
        mcpManage.setOnClickListener(v->manageMcp());
        mcpReconnect.setOnClickListener(v->{if(mcp.paired()&&mcp.enabled())startMcp();});
        findViewById(R.id.mcpBatteryButton).setOnClickListener(v->{
            if(Build.VERSION.SDK_INT>=23){try{startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));}catch(Exception e){openAppSettings();}}
            else openAppSettings();
        });
        findViewById(R.id.mcpAppSettingsButton).setOnClickListener(v->openAppSettings());
        refreshMcp();
    }
    private void openAppSettings(){startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));}
    private void refreshMcp(){
        if(mcp==null)return;
        mcpStatus.setText(mcp.status()+" · service "+(PrompterMcpService.running()?"actif":"arrêté"));
        mcpAddress.setText(mcp.paired()?mcp.base():"Aucun serveur associé");
        mcpManage.setText(mcp.paired()?"Gérer la connexion":"Associer ChatGPT");
        mcpReconnect.setEnabled(mcp.paired()&&mcp.enabled());
        boolean exempt=Build.VERSION.SDK_INT<23||((PowerManager)getSystemService(POWER_SERVICE)).isIgnoringBatteryOptimizations(getPackageName());
        mcpBattery.setText((exempt?"Optimisation batterie désactivée.":"Autorise Fond Vert Studio à fonctionner sans restriction de batterie.")+" Sur HONOR, autorise aussi les trois options de lancement manuel. Un arrêt forcé Android interrompt le service.");
    }
    private void startMcp(){
        try{
            PrompterMcpService.start(this);
            if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED
                    &&!getSharedPreferences(PREFS,MODE_PRIVATE).getBoolean("mcp_notification_requested",false)){
                getSharedPreferences(PREFS,MODE_PRIVATE).edit().putBoolean("mcp_notification_requested",true).apply();
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},1701);
            }
        }catch(Exception e){Toast.makeText(this,"Service MCP : "+e.getMessage(),Toast.LENGTH_LONG).show();}
        refreshMcp();
    }
    private void manageMcp(){
        if(mcp.paired()){
            String[] options={mcp.enabled()?"Désactiver le pilotage":"Activer le pilotage","Dissocier ce téléphone"};
            new AlertDialog.Builder(this).setTitle("Connexion ChatGPT").setItems(options,(d,i)->{
                if(i==0){boolean on=!mcp.enabled();mcp.setEnabled(on);if(on)startMcp();else PrompterMcpService.stop(this);refreshMcp();}
                else new AlertDialog.Builder(this).setTitle("Dissocier ?").setMessage("Le pilotage distant sera arrêté. Les paroles et médias sont conservés.")
                    .setNegativeButton("Annuler",null).setPositiveButton("Dissocier",(q,w)->mcp.unlink((ok,msg)->{PrompterMcpService.stop(this);refreshMcp();Toast.makeText(this,msg,Toast.LENGTH_LONG).show();})).show();
            }).setNegativeButton("Fermer",null).show();return;
        }
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(24,8,24,8);
        TextView help=new TextView(this);help.setText("Invitation CHK à usage unique et adresse HTTPS du serveur.");box.addView(help);
        EditText url=new EditText(this);url.setSingleLine(true);url.setHint("Adresse HTTPS /mcp");url.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);url.setText(mcp.base());box.addView(url);
        EditText invite=new EditText(this);invite.setSingleLine(true);invite.setHint("Invitation chkinvite_…");invite.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);box.addView(invite);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Associer ChatGPT").setView(box).setNegativeButton("Annuler",null).setPositiveButton("Associer",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            mcp.pair(url.getText().toString(),invite.getText().toString(),(ok,msg)->{
                if(isFinishing()||isDestroyed())return;
                if(ok){dialog.dismiss();startMcp();Toast.makeText(this,msg,Toast.LENGTH_LONG).show();}
                else{invite.setError(msg);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);}
            });
        }));dialog.show();
    }

    private void checkForUpdate() {
        setCheckingUi(true);
        updateStatusText.setText("Vérification de la dernière version GitHub…");
        progressText.setText("Connexion à GitHub Releases");
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.VISIBLE);

        AppUpdateManager.checkLatest(this, new AppUpdateManager.CheckCallback() {
            @Override
            public void onSuccess(AppUpdateManager.UpdateInfo info) {
                latestInfo = info;
                setCheckingUi(false);
                progressBar.setIndeterminate(false);
                progressBar.setProgress(0);
                progressBar.setVisibility(View.GONE);
                progressText.setText("");

                long now = System.currentTimeMillis();
                SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
                preferences.edit()
                        .putLong(KEY_LAST_CHECK, now)
                        .putBoolean(KEY_CACHED_AVAILABLE, info.newer)
                        .putString(KEY_CACHED_VERSION, info.version)
                        .apply();
                updateLastCheckLabel(now);

                availableVersionText.setText("Version disponible : " + info.version);
                if (info.newer) {
                    updateStatusText.setText("Mise à jour disponible.");
                    downloadButton.setVisibility(View.VISIBLE);
                    downloadButton.setEnabled(true);
                } else {
                    updateStatusText.setText("L'application est à jour.");
                    downloadButton.setVisibility(View.GONE);
                }

                String notes = info.notes == null ? "" : info.notes.trim();
                if (notes.isEmpty()) {
                    releaseNotesText.setText("Aucune note de version fournie.");
                } else {
                    releaseNotesText.setText(notes);
                }
            }

            @Override
            public void onError(String message) {
                setCheckingUi(false);
                progressBar.setVisibility(View.GONE);
                progressText.setText("");
                updateStatusText.setText("Vérification impossible : " + message);
            }
        });
    }

    private void startDownload() {
        if (latestInfo == null || !latestInfo.newer) {
            checkForUpdate();
            return;
        }

        checkButton.setEnabled(false);
        downloadButton.setEnabled(false);
        updateStatusText.setText("Téléchargement de la mise à jour…");
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(latestInfo.assetSize <= 0);
        progressBar.setProgress(0);
        progressText.setText("0 %");

        AppUpdateManager.download(this, latestInfo, new AppUpdateManager.DownloadCallback() {
            @Override
            public void onStarted(long totalBytes) {
                progressBar.setIndeterminate(totalBytes <= 0);
                if (totalBytes > 0) {
                    progressText.setText("0 % · 0 / " + formatBytes(totalBytes));
                } else {
                    progressText.setText("Téléchargement en cours…");
                }
            }

            @Override
            public void onProgress(int percent, long downloadedBytes, long totalBytes,
                                   long bytesPerSecond) {
                if (percent >= 0) {
                    progressBar.setIndeterminate(false);
                    progressBar.setProgress(percent);
                }
                StringBuilder text = new StringBuilder();
                if (percent >= 0) {
                    text.append(percent).append(" % · ");
                }
                text.append(formatBytes(downloadedBytes));
                if (totalBytes > 0) {
                    text.append(" / ").append(formatBytes(totalBytes));
                }
                if (bytesPerSecond > 0) {
                    text.append(" · ").append(formatBytes(bytesPerSecond)).append("/s");
                }
                progressText.setText(text.toString());
            }

            @Override
            public void onReady(File apkFile) {
                pendingApk = apkFile;
                progressBar.setIndeterminate(false);
                progressBar.setProgress(100);
                progressText.setText("100 % · APK vérifié");
                updateStatusText.setText("Téléchargement terminé. Ouverture de l'installation Android…");
                checkButton.setEnabled(true);
                downloadButton.setEnabled(true);
                boolean installationOpened = AppUpdateManager.installDownloadedApk(
                        SettingsActivity.this, apkFile);
                if (!installationOpened) {
                    waitingForInstallPermission = true;
                    updateStatusText.setText(
                            "Autorise Fond Vert Studio à installer cette mise à jour, puis reviens ici.");
                }
            }

            @Override
            public void onError(String message) {
                progressBar.setIndeterminate(false);
                progressBar.setVisibility(View.GONE);
                progressText.setText("");
                updateStatusText.setText("Mise à jour interrompue : " + message);
                checkButton.setEnabled(true);
                downloadButton.setEnabled(true);
            }
        });
    }

    private void setCheckingUi(boolean checking) {
        checkButton.setEnabled(!checking);
        if (checking) {
            downloadButton.setEnabled(false);
        }
    }

    private void updateLastCheckLabel(long timestamp) {
        if (timestamp <= 0L) {
            lastCheckText.setText("Dernière vérification : jamais");
            return;
        }
        java.text.DateFormat formatter = java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.SHORT, java.text.DateFormat.SHORT, Locale.getDefault());
        lastCheckText.setText("Dernière vérification : " + formatter.format(new Date(timestamp)));
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " o";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024.0) {
            return String.format(Locale.getDefault(), "%.1f Ko", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024.0) {
            return String.format(Locale.getDefault(), "%.1f Mo", mb);
        }
        return String.format(Locale.getDefault(), "%.2f Go", mb / 1024.0);
    }
}
