package dev.phonelinux.proto

/** X geometry only. Intersections are observations, never replacement ROIs. */
object ManagedComparisonGeometry {
 data class Rect(val x:Int,val y:Int,val w:Int,val h:Int) {
  init { require(w in 1..1000000 && h in 1..1000000 && x in -1000000..1000000 && y in -1000000..1000000) }
  fun numbers()=listOf(x,y,w,h)
 }
 data class Observation(val roi:Rect,val rootIntersection:Rect?,val roiIntersection:Rect?,val contained:Boolean,val coverage:String,val fit:Boolean)
 fun intersect(a:Rect,b:Rect):Rect? {
  val x=maxOf(a.x,b.x);val y=maxOf(a.y,b.y)
  val w=minOf(a.x+a.w,b.x+b.w)-x;val h=minOf(a.y+a.h,b.y+b.h)-y
  return if(w<=0 || h<=0)null else Rect(x,y,w,h)
 }
 fun observe(rootW:Int,rootH:Int,desktop:Rect):Observation {
  val root=Rect(0,0,rootW,rootH)
  // Preserve each original floor operand, including odd dimensions.
  val roi=Rect(rootW/5,rootH*35/100,rootW*3/5,rootH*3/10)
  val intersection=intersect(roi,desktop)
  val contained=intersection==roi
  return Observation(roi,intersect(root,desktop),intersection,contained,if(contained)"FULL" else if(intersection==null)"NONE" else "PARTIAL",desktop==root)
 }
 data class Window(val id:Long,val viewable:Boolean,val rect:Rect)
 fun occluders(roi:Rect,desktopId:Long,windows:List<Window>):List<String> {
  val result=mutableListOf<String>()
  for(w in windows) if(w.id!=desktopId && w.viewable && intersect(roi,w.rect)!=null) {
   check(result.size<64) { "root ROI occluder cap" };result.add(w.id.toString())
  }
  return result
 }
}
