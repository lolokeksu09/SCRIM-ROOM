package dev.lolokeksu.scrimroom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import dev.lolokeksu.scrimroom.data.LocalStore
import dev.lolokeksu.scrimroom.data.OnlineQueueState
import android.os.SystemClock
import dev.lolokeksu.scrimroom.data.RemoteClient
import dev.lolokeksu.scrimroom.data.RemoteException
import dev.lolokeksu.scrimroom.data.toOnlineQueueState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Explicit user-started queue observation; never auto-starts at boot or joins a queue. */
class QueueWatchService: Service() {
    companion object {
        private val active=MutableStateFlow(false)
        val running=active.asStateFlow()
        private data class Observation(val url:String,val token:String,val state:OnlineQueueState,val at:Long)
        @Volatile private var observation:Observation?=null
        internal fun publishForegroundState(url:String,token:String,state:OnlineQueueState){
            if(active.value) observation=Observation(url,token,state,SystemClock.elapsedRealtime())
        }
        private fun recentState(url:String,token:String):OnlineQueueState?=observation?.takeIf {
            it.url==url && it.token==token && SystemClock.elapsedRealtime()-it.at in 0L..10000L
        }?.state
        fun start(context:Context) {
            context.startForegroundService(Intent(context,QueueWatchService::class.java))
        }
        fun stop(context:Context) {context.stopService(Intent(context,QueueWatchService::class.java))}
    }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var job:Job?=null
    private val nm get()=getSystemService(NotificationManager::class.java)
    override fun onBind(intent:Intent?):IBinder?=null
    private fun notification(channel:String,title:String,body:String,ongoing:Boolean):Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this,channel).setSmallIcon(R.drawable.scrim_icon).setContentTitle(title).setContentText(body)
            .setContentIntent(open).setOngoing(ongoing).setAutoCancel(!ongoing).setVisibility(Notification.VISIBILITY_PRIVATE).build()
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="STOP"){stopSelf();return START_NOT_STICKY}
        nm.createNotificationChannel(NotificationChannel("queue_watch","Наблюдение за очередью",NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("match_ready","Найденный матч",NotificationManager.IMPORTANCE_HIGH))
        val stop=PendingIntent.getService(this,1,Intent(this,QueueWatchService::class.java).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE)
        val notice=Notification.Builder(this,"queue_watch").setSmallIcon(R.drawable.scrim_icon).setContentTitle("SCRIM ROOM")
            .setContentText("Наблюдение за очередью включено").setOngoing(true).addAction(Notification.Action.Builder(null,"Остановить",stop).build()).build()
        startForeground(701,notice)
        if(job?.isActive==true)return START_NOT_STICKY
        active.value=true
        job=scope.launch {
            val store=LocalStore(this@QueueWatchService)
            val url=store.readServerUrl()
            val token=store.readServerToken()
            var last=""
            var errors=0
            try {
                while(isActive && token.isNotBlank() && store.readServerUrl()==url && store.readServerToken()==token){
                    try {
                        val state=recentState(url,token) ?: RemoteClient(url).state(token).toOnlineQueueState()
                        errors=0
                        val match=state.match
                        if(match?.status=="READY_CHECK" && !match.ownReady){
                            if(last!=match.id){
                                nm.notify(702,notification("match_ready","Матч найден","Открой SCRIM ROOM и подтверди готовность",false))
                                last=match.id
                            }
                        }else{
                            nm.cancel(702)
                            if(!state.queued && match?.status!="READY_CHECK")break
                        }
                    }catch(e:CancellationException){throw e}
                    catch(e:Exception){
                        if(e is RemoteException && e.statusCode==401)break
                        if(++errors>=3)break
                    }
                    delay(5000)
                }
            }finally{stopSelf()}
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId:Int,fServiceType:Int){stopSelf()}
    override fun onDestroy(){scope.cancel();active.value=false;observation=null;nm.cancel(702);stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()}
}
