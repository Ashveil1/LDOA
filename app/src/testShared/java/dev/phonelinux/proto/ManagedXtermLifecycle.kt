package dev.phonelinux.proto

/** Managed CI only. Same owned window, finite scene preparation, no reacquisition. */
internal class ManagedXtermLifecycle(
    private val expected: List<Pair<Int,String>>,
    private val readStart: (Int)->String?,
    private val queryMap: ()->MapState,
    private val emit: (String)->Unit,
    private val clock: ()->Long = System::nanoTime
) {
    enum class MapState { NOT_ATTEMPTED, UNKNOWN, IsUnMapped, IsUnviewable, IsViewable }
    data class Observation(val same:Boolean,val map:MapState)
    companion object {
        const val CONSECUTIVE=3
        const val MAX_OBSERVATIONS=24
        const val PREPARE_NS=20_000_000_000L
        const val RESTORE_NS=30_000_000_000L
        const val PAUSE_MS=500L
        fun selectWm(owners:List<Triple<Int,String,String>>,emit:(String)->Unit):Triple<Int,String,String> {
            emit("LIFE_WM count=${owners.size}")
            check(owners.size==1){"managed WM zero/multiple ownership"}
            val wm=owners.single()
            check(wm.first>0&&wm.second.toLongOrNull()?.let{it>0}==true&&wm.third in setOf("jwm","openbox","icewm","fluxbox"))
            emit("LIFE_WM pid=${wm.first} start=${wm.second} kind=${wm.third.uppercase(java.util.Locale.ROOT)}")
            return wm
        }
    }
    init { require(expected.size==3&&expected.all{it.first>0&&it.second.toLongOrNull()?.let{n->n>0}==true}) }
    var restoreObligation=false; private set
    private var restoreAttempted=false
    fun minimize(action:()->Unit) { restoreObligation=true;action() }
    fun restore(action:()->Unit) {
        check(!restoreAttempted){"managed restore single attempt"}
        restoreAttempted=true
        action()
        restoreObligation=false
    }
    fun finish(primary:Throwable?,action:()->Unit) {
        if(restoreObligation&&!restoreAttempted)try{restore(action)}catch(t:Throwable){if(primary!=null)primary.addSuppressed(t) else throw t}
    }
    fun observe(transition:Int,phase:String):Observation {
        require(transition in -1..4&&phase in setOf("INITIAL","PREPARE","PRE_CAPTURE","POST_CAPTURE","RESTORE","INPUT"))
        val start=clock()
        // Do not short-circuit reads: each process has a distinct recorded result.
        val actual=expected.map { try{readStart(it.first)}catch(_:Throwable){null} }
        var same=actual.indices.all{actual[it]==expected[it].second}
        var after=actual
        var map=MapState.NOT_ATTEMPTED
        try { if(same)map=queryMap() }
        catch(t:Throwable){map=MapState.UNKNOWN;throw t}
        finally {
            after=expected.map { try{readStart(it.first)}catch(_:Throwable){null} }
            same=same&&after.indices.all{after[it]==expected[it].second}
            val owners=expected.indices.joinToString(" "){i->
                val value=actual[i]?.toLongOrNull()?.takeIf{it>0}
                "p${i}=${expected[i].first} e${i}=${expected[i].second} o${i}=${value ?: -1} id${i}=${if(value==null)"UNKNOWN" else if(actual[i]==expected[i].second)"SAME" else "CHANGED"} post${i}=${after[i]?.toLongOrNull()?.takeIf{it>0} ?: -1}"
            }
            emit("LIFE transition=$transition phase=$phase begin=$start end=${clock()} $owners map=${map.name}")
        }
        check(same){"managed lifecycle identity"}
        check(map!=MapState.NOT_ATTEMPTED&&map!=MapState.UNKNOWN){"managed lifecycle map UNKNOWN"}
        return Observation(same,map)
    }
    /** One minimize maximum per transition, never a postcapture repair. */
    fun prepare(transition:Int,until:Long,minimize:()->Unit,pause:()->Unit) {
        var sent=false;var consecutive=0
        repeat(MAX_OBSERVATIONS) {
            check(clock()<until){"managed lifecycle deadline"}
            val o=observe(transition,"PREPARE")
            check(clock()<until){"managed lifecycle late observation"}
            if(o.map==MapState.IsUnMapped)consecutive++ else {
                consecutive=0
                if(!sent){sent=true;minimize()}
            }
            if(consecutive==CONSECUTIVE)return
            pause()
        }
        error("managed lifecycle observation cap")
    }
    fun requireUnmapped(transition:Int,phase:String) {
        check(observe(transition,phase).map==MapState.IsUnMapped){"managed lifecycle mapped"}
    }
    /** Shared actual query wrapper: evidence is durably emitted before validation. */
    data class Query(val text:String,val rc:Int,val eof:Boolean,val overflow:Boolean,val deadline:Boolean,val complete:Boolean,val cleanup:Boolean,val failure:Throwable?=null)
    fun query(transition:Int,op:String,run:()->Query):String {
        require(op in setOf("MAP","MINIMIZE","ACTIVATE","FOCUS"))
        val begin=clock();var result:Query?=null
        try { result=run() }
        finally {
            val q=result
            emit("LIFE_QUERY transition=$transition op=$op begin=$begin end=${clock()} attempted=1 rc=${q?.rc ?: -2} eof=${q?.eof?.let{if(it)1 else 0} ?: -1} overflow=${q?.overflow?.let{if(it)1 else 0} ?: -1} deadline=${q?.deadline?.let{if(it)1 else 0} ?: -1} complete=${q?.complete?.let{if(it)1 else 0} ?: -1} cleanup=${q?.cleanup?.let{if(it)1 else 0} ?: -1}")
        }
        val q=result!!
        q.failure?.let{throw it}
        check(q.rc==0&&q.eof&&!q.overflow&&!q.deadline&&q.complete&&q.cleanup){"managed lifecycle query failed"}
        return q.text
    }
}
