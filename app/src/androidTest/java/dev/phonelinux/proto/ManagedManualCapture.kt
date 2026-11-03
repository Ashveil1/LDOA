package dev.phonelinux.proto

import androidx.test.platform.app.InstrumentationRegistry

internal object ManagedManualCapture {
    // Controlled managed CI scene only. Primitive public state, no title,
    // hierarchy payload, private field or presentation certification.
    fun snapshot(ctx:android.content.Context):ManagedManualSnapshot {
        val begin=System.nanoTime()
        var numbers=emptyList<Double>();var complete=false
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val acts=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .filter {it.javaClass.name=="com.termux.x11.MainActivity"&&it.hasWindowFocus()}
                val act=acts.singleOrNull()
                if(act!=null&&!act.isFinishing&&!act.isDestroyed) {
                    val id=act.resources.getIdentifier("lorieView","id",ctx.packageName)
                    val v=act.findViewById<com.termux.x11.LorieView>(id) ?: error("snapshot missing")
                    val loc=IntArray(2);v.getLocationOnScreen(loc)
                    val visible=android.graphics.Rect();val shown=v.getGlobalVisibleRect(visible)
                    val sf=android.graphics.Rect(v.holder.surfaceFrame)
                    val av=v.availableRect;val dst=v.inputViewport;val src=v.inputSourceRect
                    val matrix=FloatArray(9);v.matrix.getValues(matrix)
                    val ins=v.rootWindowInsets
                    val bars=ins?.getInsets(android.view.WindowInsets.Type.systemBars())
                    val ime=ins?.getInsets(android.view.WindowInsets.Type.ime())
                    val display=v.display
                    numbers=listOf(v.measuredWidth,v.measuredHeight,v.width,v.height,
                        loc[0],loc[1],visible.left,visible.top,visible.right,visible.bottom,
                        sf.left,sf.top,sf.right,sf.bottom,av.left,av.top,av.right,av.bottom,
                        dst.left,dst.top,dst.right,dst.bottom).map { it.toDouble() }+
                        listOf(src.left,src.top,src.right,src.bottom).map { it.toDouble() }+
                        matrix.map { it.toDouble() }+
                        listOf(v.alpha.toDouble(),if(v.holder.surface.isValid)1.0 else 0.0,
                            if(shown)1.0 else 0.0,if(v.isShown)1.0 else 0.0,v.visibility.toDouble(),
                            if(act.hasWindowFocus())1.0 else 0.0,(display?.displayId ?: -1).toDouble(),
                            (display?.rotation ?: -1).toDouble(),
                            (bars?.left ?: -1).toDouble(),(bars?.top ?: -1).toDouble(),
                            (bars?.right ?: -1).toDouble(),(bars?.bottom ?: -1).toDouble(),
                            (ime?.left ?: -1).toDouble(),(ime?.top ?: -1).toDouble(),
                            (ime?.right ?: -1).toDouble(),(ime?.bottom ?: -1).toDouble(),
                            if(ins?.isVisible(android.view.WindowInsets.Type.ime())==true)1.0 else 0.0,
                            if(ins!=null)1.0 else 0.0)
                    complete=true
                }
            }
        }catch(_:Throwable){complete=false}
        return ManagedManualSnapshot(begin,System.nanoTime(),numbers,complete)
    }

}
