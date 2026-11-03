package dev.phonelinux.proto
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.runner.Description
import org.junit.runner.Result
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener
/** Opt-in runner listener exports actual JUnit events, including @After. */
class ManagedXmlListener:RunListener() {
    private val cases=linkedMapOf<String,Pair<Description,StringBuilder>>()
    private fun key(d:Description)="${d.className}#${d.methodName}"
    private fun esc(s:String)=s.replace("&","&amp;").replace("<","&lt;").replace("\"","&quot;")
    private val started=mutableSetOf<String>()
    private val skipped=mutableSetOf<String>()
    override fun testStarted(d:Description) {
        val state=cases.getOrPut(key(d)){d to StringBuilder()}.second
        if(!started.add(key(d))) state.append("<failure message=\"duplicate test start\"/>")
    }
    override fun testFailure(f:Failure) { cases.getOrPut(key(f.description)){ f.description to StringBuilder() }.second.append("<failure message=\"").append(esc(f.message?:"failure")).append("\"/>") }
    override fun testAssumptionFailure(f:Failure) {
        if(skipped.add(key(f.description))) cases.getOrPut(key(f.description)){f.description to StringBuilder()}.second.append("<skipped/>")
    }
    override fun testIgnored(d:Description) {
        if(skipped.add(key(d))) cases.getOrPut(key(d)){d to StringBuilder()}.second.append("<skipped/>")
    }
    override fun testRunFinished(r:Result) {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val dir=ctx.externalMediaDirs.first().let {File(it,"additional_test_output")};check(dir.isDirectory||dir.mkdirs())
        val xml=StringBuilder("<?xml version=\"1.0\"?><testsuite tests=\"${r.runCount}\" failures=\"${r.failureCount}\" skipped=\"${skipped.size}\">")
        for((d,state) in cases.values) xml.append("<testcase classname=\"").append(esc(d.className?:"unknown")).append("\" name=\"").append(esc(d.methodName?:"unknown")).append("\">").append(state).append("</testcase>")
        xml.append("</testsuite>");File(dir,"managed-junit.xml").writeText(xml.toString())
    }
}
