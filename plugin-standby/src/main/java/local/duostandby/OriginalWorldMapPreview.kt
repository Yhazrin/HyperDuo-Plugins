package local.duostandby

import android.content.Context
import android.graphics.*
import java.time.Instant
import kotlin.math.min
import kotlin.math.floor

/** Visible research slice, NOT a completed WorldClockFace or confirmed Duo map branch.
 * 24A94425 ClockPoster: legacy map b7dc4, path producer 1ebc4, sampler 525d0.
 * The original map factory and fit formula are recovered; Android window remains
 * an explicit host adapter. Duo host inputs, dashboard and city pins remain open.
 * Do not add invented labels, city locations, card chrome or customization.
 */
class OriginalWorldMapPreview(context: Context) {
    private val bitmap = context.assets.open("original_world/World_Clock_Dotted_Map.pdf-2d685ee3ec7a3bb0.png").use {
        BitmapFactory.decodeStream(it) ?: error("Missing decoded original world map")
    }
    private val density = context.resources.displayMetrics.density.toDouble()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var lastSecond = Long.MIN_VALUE
    private var cached: OriginalWorldTerminator.Terminator? = null

    fun draw(canvas: Canvas, width: Int, height: Int, instant: Instant, redMode: Boolean) {
        canvas.drawColor(Color.BLACK)
        // Source fit0xb7c6c, source margins/landInsets. Android supplies its
        // window as the explicit host input; this is not a measured Duo scene.
        val geometry=OriginalWorldMapGeometry.fit(width/density,height/density,density)
        val frame=RectF((geometry.x*density).toFloat(),(geometry.y*density).toFloat(),
            ((geometry.x+geometry.width)*density).toFloat(),((geometry.y+geometry.height)*density).toFloat())
        val mapWidth=frame.width();val mapHeight=frame.height()
        if(mapWidth<=0f || mapHeight<=0f)return
        if (lastSecond != instant.epochSecond) {
            cached = OriginalWorldTerminator.calculate(instant); lastSecond = instant.epochSecond
        }
        val terminator = cached ?: return
        val points = OriginalWorldPath.projectLegacy(terminator, mapWidth.toDouble()/density, density)
        val path = Path()
        val polarY = if (terminator.northernHemisphereSummer) mapHeight else 0f
        path.moveTo(0f,polarY)
        points.forEach { path.lineTo((it.x*density).toFloat(),(it.y*density).toFloat()) }
        path.lineTo(mapWidth,polarY); path.lineTo(0f,polarY)
        val save = canvas.save()
        canvas.translate(frame.left,frame.top);canvas.clipRect(0f,0f,mapWidth,mapHeight)
        // Original Map content has white.opacity(0.8) / RGB(0.6,0,0).
        paint.reset();paint.isAntiAlias=true;paint.isFilterBitmap=true
        paint.colorFilter=PorterDuffColorFilter(if(redMode)Color.rgb(153,0,0)else Color.WHITE,PorterDuff.Mode.SRC_IN)
        paint.alpha=if(redMode)255 else 204
        canvas.drawBitmap(bitmap,null,RectF(0f,0f,mapWidth,mapHeight),paint)
        // 4d83c..4d8e4: normal black.opacity(0.6), red mode opacity(0.4).
        paint.reset();paint.isAntiAlias=true;paint.color=Color.BLACK;paint.alpha=if(redMode)102 else 153
        canvas.drawPath(path,paint)
        // 4d9f4 open path; 4da40 stroke4pt, round cap, miter join10.
        // 4db80 -> ba6c: vertical mask stops at 0/.05/.2/.6,
        // white opacity .15/1/1/.15. It is NOT a second closed polygon.
        val outline=OriginalWorldPath.projectLegacy(terminator,mapWidth/density,density,false)
        if(outline.isNotEmpty()){
            val line=Path();line.moveTo((outline[0].x*density).toFloat(),(outline[0].y*density).toFloat())
            outline.forEach { line.lineTo((it.x*density).toFloat(),(it.y*density).toFloat()) }
            val ink=if(redMode)Color.rgb(153,0,0)else Color.rgb(148,148,148)
            val faded=(ink and 0x00ffffff) or (38 shl 24)
            paint.reset();paint.isAntiAlias=true;paint.style=Paint.Style.STROKE
            paint.strokeWidth=(4.0*density).toFloat();paint.strokeCap=Paint.Cap.ROUND;paint.strokeJoin=Paint.Join.MITER;paint.strokeMiter=10f
            paint.shader=LinearGradient(0f,0f,0f,mapHeight,intArrayOf(faded,ink,ink,faded),floatArrayOf(0f,.05f,.2f,.6f),Shader.TileMode.CLAMP)
            canvas.drawPath(line,paint)
        }
        canvas.restoreToCount(save)
    }
}

/** Source's sampled-line Catmull-Rom, including its nonstandard skipped P1 endpoint. */
object OriginalWorldPath {
    data class Point(val x: Double, val y: Double)
    fun sample(points: List<Point>, resolution: Int): List<Point> {
        if (points.size < 4) return points
        require(resolution >= 1)
        val result = ArrayList<Point>(2+(points.size-3)*resolution)
        result.add(points.first())
        val inv = 1.0/resolution
        for (i in 1 until points.size-2) {
            val p0=points[i-1];val p1=points[i];val p2=points[i+1];val p3=points[i+2]
            for (j in 1 until resolution) {
                val t=inv*j;val t2=t*t;val t3=Math.pow(t,3.0)
                fun axis(a:Double,b:Double,c:Double,d:Double):Double =
                    0.5*(2*b+(c-a)*t+(2*a-5*b+4*c-d)*t2+(3*b-a-3*c+d)*t3)
                result.add(Point(axis(p0.x,p1.x,p2.x,p3.x),axis(p0.y,p1.y,p2.y,p3.y)))
            }
            result.add(p2)
        }
        result.add(points.last());return result
    }
    fun projectLegacy(t:OriginalWorldTerminator.Terminator, width:Double, displayScale:Double,closed:Boolean=true):List<Point> {
        val scale=width/737.0
        fun screen(v:Double)=floor(v*displayScale+0.5)/displayScale
        val points=t.coordinates.map { Point(
            screen(((it.longitude+180.0)*737.0/360.0)*scale),
            screen(((90.0-it.latitude)*369.0/180.0)*scale)) }.let { points ->
            if(closed)points else points.filter { it.x>4.0 && it.x<width-4.0 }
        }
        return t.splineResolution?.let { sample(points,it) } ?: points
    }
}
