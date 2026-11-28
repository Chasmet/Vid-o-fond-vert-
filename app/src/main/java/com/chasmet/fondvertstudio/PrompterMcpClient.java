package com.chasmet.fondvertstudio;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Foreground-only client of the existing CHK Universal Control HTTPS protocol. */
final class PrompterMcpClient {
    interface Target { JSONObject execute(String action,JSONObject args)throws Exception; }
    interface Callback { void done(boolean ok,String message); }
    private static final String[] READ={"prompter_status","lyrics_list"};
    private static final String[] WRITE={"lyrics_add","lyrics_update","lyrics_delete","lyrics_restore","prompter_seek","prompter_play","prompter_open_camera","lyrics_export_pdf"};
    private final AtomicFile store;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService io=Executors.newSingleThreadScheduledExecutor();
    private final Target target;
    private JSONObject state=new JSONObject();
    private volatile boolean active,closed,enabled,paired;
    private volatile String status="Non associé",base="";
    private volatile long epoch;
    private ScheduledFuture<?> polling;
    PrompterMcpClient(Context c,Target t){
        target=t;
        store=new AtomicFile(new File(c.getNoBackupFilesDir(),"fondvert-mcp.json"));
        try(FileInputStream in=store.openRead()){state=new JSONObject(read(in,500000));}catch(Exception ignored){}
        base=state.optString("base");paired=!state.optString("id").isEmpty()&&!state.optString("secret").isEmpty();enabled=state.optBoolean("enabled",false);
        status=paired?(enabled?"Associé · ouverture du prompteur requise":"Associé · désactivé"):"Non associé";
    }
    String status(){return status;}
    String base(){return base;}
    boolean paired(){return paired;}
    boolean enabled(){return enabled;}
    static String normalize(String input){
        try{
            URI u=new URI(input.trim());String path=u.getPath();
            if(!"https".equalsIgnoreCase(u.getScheme())||u.getHost()==null||u.getRawUserInfo()!=null||u.getRawQuery()!=null||u.getRawFragment()!=null||!(path==null||path.isEmpty()||path.equals("/")||path.equals("/mcp")||path.equals("/mcp/")))throw new Exception();
            return "https://"+u.getRawAuthority();
        }catch(Exception e){throw new IllegalArgumentException("Adresse HTTPS racine ou /mcp uniquement, sans jeton.");}
    }
    private static JSONArray caps()throws Exception{
        JSONArray a=new JSONArray();
        for(String name:READ)a.put(new JSONObject().put("name",name).put("readOnly",true).put("description","Lire le projet ou les paroles de Fond Vert Studio"));
        for(String name:WRITE)a.put(new JSONObject().put("name",name).put("readOnly",false).put("description","Agir sur le prompteur après autorisation du propriétaire"));
        return a;
    }
    private static boolean allowed(String action){for(String v:READ)if(v.equals(action))return true;for(String v:WRITE)if(v.equals(action))return true;return false;}
    void pair(String address,String code,Callback callback){
        final String host;
        try{host=normalize(address);if(!code.trim().matches("chkinvite_[A-Za-z0-9_-]{20,}"))throw new IllegalArgumentException("Invitation CHK invalide.");}
        catch(Exception e){callback.done(false,e.getMessage());return;}
        final String invite=code.trim();
        io.execute(()->{try{
            if(paired)throw new IOException("Dissocie d'abord l'ancien serveur.");
            JSONObject result=request(host,"POST","/api/onboard",new JSONObject().put("name","Fond Vert Studio").put("invite",invite).put("capabilities",caps()),null);
            String id=result.getString("id"),secret=result.getString("secret");
            if(!id.matches("[a-f0-9-]{36}")||!secret.startsWith("chkapp_"))throw new IOException("Identifiants serveur invalides");
            JSONObject next=new JSONObject().put("base",host).put("id",id).put("secret",secret).put("enabled",true);
            write(next);state=next;base=host;paired=true;enabled=true;status="Associé · prêt";
            main.post(()->{if(!closed)callback.done(true,"Fond Vert Studio associé.");});
        }catch(Exception e){status="Association impossible";main.post(()->{if(!closed)callback.done(false,safe(e));});}});
    }
    void setEnabled(boolean on){
        enabled=on&&paired;epoch++;
        io.execute(()->{try{state.put("enabled",enabled);write(state);status=enabled?"Associé · prêt":"Associé · désactivé";}catch(Exception e){enabled=false;status="Enregistrement local impossible";}});
    }
    void unlink(Callback callback){
        enabled=false;epoch++;
        io.execute(()->{try{JSONObject empty=new JSONObject();write(empty);state=empty;base="";paired=false;status="Non associé";main.post(()->{if(!closed)callback.done(true,"Association locale supprimée. Révoque aussi l'application sur le serveur.");});}catch(Exception e){main.post(()->{if(!closed)callback.done(false,"Dissociation impossible");});}});
    }
    void resume(){if(closed)return;active=true;epoch++;if(polling==null)polling=io.scheduleWithFixedDelay(this::poll,0,3,TimeUnit.SECONDS);}
    void pause(){active=false;epoch++;}
    void close(){pause();closed=true;if(polling!=null)polling.cancel(false);io.shutdown();}
    private void write(JSONObject value)throws Exception{
        FileOutputStream out=store.startWrite();
        try{out.write(value.toString().getBytes(StandardCharsets.UTF_8));store.finishWrite(out);}catch(Exception e){store.failWrite(out);throw e;}
    }
    private void poll(){
        if(!active||closed||!enabled||!paired)return;
        long session=epoch;String host=base,id=state.optString("id"),secret=state.optString("secret");
        try{
            String route="/api/agent/"+id;
            // A leased operation is never blindly replayed after interruption.
            JSONObject pending=state.optJSONObject("pending");
            if(pending!=null){
                if(!pending.has("result"))pending.put("result",new JSONObject().put("ok",false).put("execution_state","unknown").put("message","Interruption Android : vérifier l'effet avant de relancer."));
                try{request(host,"POST",route+"/result",pending,secret);}catch(IOException e){if(!("HTTP 404".equals(e.getMessage())||"HTTP 409".equals(e.getMessage())||"HTTP 410".equals(e.getMessage())))throw e;}
                state.put("last",pending);state.remove("pending");write(state);
            }
            JSONObject response=request(host,"GET",route+"/poll",null,secret);status="Connecté · prompteur disponible";
            JSONObject task=response.optJSONObject("task");if(task==null)return;
            String tid=task.getString("id");if(!tid.matches("[a-f0-9-]{36}"))throw new IOException("Tâche invalide");
            JSONObject last=state.optJSONObject("last");
            if(last!=null&&tid.equals(last.optString("task_id"))){request(host,"POST",route+"/result",last,secret);return;}
            JSONObject journal=new JSONObject().put("task_id",tid);state.put("pending",journal);write(state);
            String action=task.getString("action");JSONObject args=task.optJSONObject("args");final JSONObject params=args==null?new JSONObject():args;
            JSONObject result;
            if(!allowed(action))result=new JSONObject().put("ok",false).put("message","Commande non autorisée");
            else {
                FutureTask<JSONObject> work=new FutureTask<>(()->{
                    if(!active||closed||!enabled||session!=epoch)throw new IOException("Ouvre le prompteur et active MCP");
                    return target.execute(action,params);
                });
                main.post(work);
                try{result=work.get(15,TimeUnit.SECONDS);}
                catch(TimeoutException ex){work.cancel(false);result=new JSONObject().put("ok",false).put("execution_state","unknown").put("message","Réponse Android non confirmée ; vérifier avant de réessayer.");}
                catch(Exception ex){Throwable cause=ex.getCause();result=new JSONObject().put("ok",false).put("message",cause==null?"Commande interrompue":String.valueOf(cause.getMessage()));}
            }
            journal.put("result",result);state.put("pending",journal);write(state);
            request(host,"POST",route+"/result",journal,secret);state.put("last",journal);state.remove("pending");write(state);
            status=result.optBoolean("ok")?"Connecté · dernière commande réussie":"Connecté · commande refusée ou non confirmée";
        }catch(Exception e){status="MCP : "+safe(e);}
    }
    private static String safe(Exception e){String m=e.getMessage();if(m!=null&&m.matches("HTTP [0-9]{3}"))return m;if(e instanceof SocketTimeoutException)return "délai réseau dépassé";if(e instanceof UnknownHostException)return "serveur introuvable";return e.getClass().getSimpleName();}
    private static String read(InputStream in,int limit)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int k;while((k=in.read(b))!=-1){if(out.size()+k>limit)throw new IOException("Message trop long");out.write(b,0,k);}return new String(out.toByteArray(),StandardCharsets.UTF_8);
    }
    private static JSONObject request(String host,String method,String route,JSONObject body,String secret)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(host+route).openConnection();
        try{c.setRequestMethod(method);c.setConnectTimeout(8000);c.setReadTimeout(20000);c.setInstanceFollowRedirects(false);c.setRequestProperty("Accept","application/json");
            if(secret!=null)c.setRequestProperty("Authorization","Bearer "+secret);
            if(body!=null){byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>320000)throw new IOException("Message trop long");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");try(OutputStream out=c.getOutputStream()){out.write(bytes);}}
            int http=c.getResponseCode();if(http!=200&&http!=201)throw new IOException("HTTP "+http);
            try(InputStream in=c.getInputStream()){return new JSONObject(read(in,350000));}
        }finally{c.disconnect();}
    }
}
