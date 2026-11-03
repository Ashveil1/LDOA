package dev.phonelinux.proto

/** Immutable primitive copies only. Describes Java state, never presented pixels. */
internal class ManagedManualSnapshot(start:Long,end:Long,values:List<Double>,complete:Boolean) {
    val startNs=start
    val endNs=end
    val numbers=java.util.Collections.unmodifiableList(ArrayList(values))
    val complete=complete
    fun valid():Boolean=complete&&startNs>0&&endNs>=startNs&&endNs-startNs<=2_000_000_000L&&
        numbers.size==53&&numbers.all { it.isFinite()&&kotlin.math.abs(it)<=1_000_000.0 }&&
        numbers[0]>0&&numbers[1]>0&&numbers[2]>0&&numbers[3]>0&&numbers[36]==1.0&&
        numbers[37]==1.0&&numbers[38]==1.0&&numbers[39]==0.0&&numbers[40]==1.0&&numbers[52]==1.0&&numbers[41]>=0.0&&numbers[42] in listOf(0.0,1.0,2.0,3.0)
}
