package local.duostandby
/** Original24A94425: 8 plugin looks +7 rainbow +12 extended, exact sRGB assets.
 * Main-bundle Colors overrides precede shared framework. Night-red treatment is separate. */
object OriginalDigitalColors {
 data class Colors(val background:Int,val numerals:Int,val date:Int,val widgets:Int)
 val ids=listOf("color-0","color-1","color-2","color-3","color-4","color-5","color-6","color-7","cream","green","yellow","orange","pink","purple","blue","green-0","yellow-0","orange-0","pink-0","purple-0","blue-0","green-1","yellow-1","orange-1","pink-1","purple-1","blue-1")
 val labels=listOf("青瓷绿","迷雾灰","石英粉","砂岩黄","深空蓝配海军蓝","深金色配深棕褐色","海蓝色配深蓝绿色","浅卡其色配深褐色","亚麻色配黑色","绿色配黑色","黄色配黑色","橙色配黑色","粉色配黑色","紫色配黑色","蓝色配黑色","薄荷色配常青树色","淡黄色配胡桃褐","浅桃色配焦赭色","胭红色配深勃艮第酒红色","薰衣草紫配深紫色","天蓝色配午夜蓝","绿色配常青树色","金盏花色配胡桃褐","橙色配焦赭色","粉色配深勃艮第酒红色","紫色配深紫色","蓝色配午夜蓝")
 private val light=arrayOf(
  Colors(0xffa4b89d.toInt(),0xff000000.toInt(),0xff000000.toInt(),0xffffffff.toInt()), // color-0
  Colors(0xff92abb1.toInt(),0xff000000.toInt(),0xff000000.toInt(),0xffffffff.toInt()), // color-1
  Colors(0xffac9b9f.toInt(),0xff000000.toInt(),0xff000000.toInt(),0xffffffff.toInt()), // color-2
  Colors(0xffaba398.toInt(),0xff000000.toInt(),0xff000000.toInt(),0xffffffff.toInt()), // color-3
  Colors(0xff326593.toInt(),0xffffd739.toInt(),0xffffd739.toInt(),0xffffffff.toInt()), // color-4
  Colors(0xffa98400.toInt(),0xffbad9c6.toInt(),0xffbad9c6.toInt(),0xffffffff.toInt()), // color-5
  Colors(0xff245b6e.toInt(),0xffffceda.toInt(),0xffffceda.toInt(),0xffffffff.toInt()), // color-6
  Colors(0xffdbd0b9.toInt(),0xffff776c.toInt(),0xffff776c.toInt(),0xffffffff.toInt()), // color-7
  Colors(0xffd6d2ce.toInt(),0xff000000.toInt(),0xff000000.toInt(),0xffffffff.toInt()), // cream
  Colors(0xff3e935c.toInt(),0xff000000.toInt(),0xff000000.toInt(),0xff000000.toInt()), // green
  Colors(0xffedd142.toInt(),0xff000000.toInt(),0xff000000.toInt(),0xff000000.toInt()), // yellow
  Colors(0xffe3704b.toInt(),0xff000000.toInt(),0xff000000.toInt(),0xff000000.toInt()), // orange
  Colors(0xffdd5f7d.toInt(),0xff000000.toInt(),0xff000000.toInt(),0xff000000.toInt()), // pink
  Colors(0xff827eb2.toInt(),0xff000000.toInt(),0xff000000.toInt(),0xff000000.toInt()), // purple
  Colors(0xff547eae.toInt(),0xff000000.toInt(),0xff000000.toInt(),0xff000000.toInt()), // blue
  Colors(0xffbad9c6.toInt(),0xff3e935c.toInt(),0xff3e935c.toInt(),0xff3e935c.toInt()), // green-0
  Colors(0xfffceeb7.toInt(),0xffffba3e.toInt(),0xffffba3e.toInt(),0xffffba3e.toInt()), // yellow-0
  Colors(0xffffcbb0.toInt(),0xffe3704b.toInt(),0xffe3704b.toInt(),0xffe3704b.toInt()), // orange-0
  Colors(0xffffceda.toInt(),0xffdd5f7d.toInt(),0xffdd5f7d.toInt(),0xffdd5f7d.toInt()), // pink-0
  Colors(0xffc8c4e5.toInt(),0xff827eb2.toInt(),0xff827eb2.toInt(),0xff827eb2.toInt()), // purple-0
  Colors(0xffbacee8.toInt(),0xff547eae.toInt(),0xff547eae.toInt(),0xff547eae.toInt()), // blue-0
  Colors(0xff3e935c.toInt(),0xffbad9c6.toInt(),0xffbad9c6.toInt(),0xffbad9c6.toInt()), // green-1
  Colors(0xffffba3e.toInt(),0xfffceeb7.toInt(),0xfffceeb7.toInt(),0xfffceeb7.toInt()), // yellow-1
  Colors(0xffe3704b.toInt(),0xffffcbb0.toInt(),0xffffcbb0.toInt(),0xffffcbb0.toInt()), // orange-1
  Colors(0xffdd5f7d.toInt(),0xffffceda.toInt(),0xffffceda.toInt(),0xffffceda.toInt()), // pink-1
  Colors(0xff827eb2.toInt(),0xffc8c4e5.toInt(),0xffc8c4e5.toInt(),0xffc8c4e5.toInt()), // purple-1
  Colors(0xff547eae.toInt(),0xffbacee8.toInt(),0xffbacee8.toInt(),0xffbacee8.toInt()), // blue-1
 )
 private val dark=arrayOf(
  Colors(0xff000000.toInt(),0xffa4b89d.toInt(),0xffa4b89d.toInt(),0xffffffff.toInt()), // color-0
  Colors(0xff000000.toInt(),0xff92abb1.toInt(),0xff92abb1.toInt(),0xffffffff.toInt()), // color-1
  Colors(0xff000000.toInt(),0xffac9b9f.toInt(),0xffac9b9f.toInt(),0xffffffff.toInt()), // color-2
  Colors(0xff000000.toInt(),0xffaba398.toInt(),0xffaba398.toInt(),0xffffffff.toInt()), // color-3
  Colors(0xff102d48.toInt(),0xffffd739.toInt(),0xffffd739.toInt(),0xffffffff.toInt()), // color-4
  Colors(0xff4a3a01.toInt(),0xffbad9c6.toInt(),0xffbad9c6.toInt(),0xffffffff.toInt()), // color-5
  Colors(0xff12333f.toInt(),0xffffceda.toInt(),0xffffceda.toInt(),0xffffffff.toInt()), // color-6
  Colors(0xff3d382f.toInt(),0xffff776c.toInt(),0xffff776c.toInt(),0xffffffff.toInt()), // color-7
  Colors(0xff000000.toInt(),0xffd6d2ce.toInt(),0xffd6d2ce.toInt(),0xffffffff.toInt()), // cream
  Colors(0xff000000.toInt(),0xff3e935c.toInt(),0xff3e935c.toInt(),0xff3e935c.toInt()), // green
  Colors(0xff000000.toInt(),0xffedd142.toInt(),0xffedd142.toInt(),0xffedd142.toInt()), // yellow
  Colors(0xff000000.toInt(),0xffe3704b.toInt(),0xffe3704b.toInt(),0xffe3704b.toInt()), // orange
  Colors(0xff000000.toInt(),0xffdd5f7d.toInt(),0xffdd5f7d.toInt(),0xffdd5f7d.toInt()), // pink
  Colors(0xff000000.toInt(),0xff827eb2.toInt(),0xff827eb2.toInt(),0xff827eb2.toInt()), // purple
  Colors(0xff000000.toInt(),0xff547eae.toInt(),0xff547eae.toInt(),0xff547eae.toInt()), // blue
  Colors(0xff193b25.toInt(),0xff3e935c.toInt(),0xff3e935c.toInt(),0xff3e935c.toInt()), // green-0
  Colors(0xff6d3e07.toInt(),0xffffba3e.toInt(),0xffffba3e.toInt(),0xffffba3e.toInt()), // yellow-0
  Colors(0xff5b2d1e.toInt(),0xffe3704b.toInt(),0xffe3704b.toInt(),0xffe3704b.toInt()), // orange-0
  Colors(0xff4d2528.toInt(),0xffdd5f7d.toInt(),0xffdd5f7d.toInt(),0xffdd5f7d.toInt()), // pink-0
  Colors(0xff343247.toInt(),0xff827eb2.toInt(),0xff827eb2.toInt(),0xff827eb2.toInt()), // purple-0
  Colors(0xff223246.toInt(),0xff547eae.toInt(),0xff547eae.toInt(),0xff547eae.toInt()), // blue-0
  Colors(0xff193b25.toInt(),0xffbad9c6.toInt(),0xffbad9c6.toInt(),0xffbad9c6.toInt()), // green-1
  Colors(0xff6d3e07.toInt(),0xfffceeb7.toInt(),0xfffceeb7.toInt(),0xfffceeb7.toInt()), // yellow-1
  Colors(0xff5b2d1e.toInt(),0xffffcbb0.toInt(),0xffffcbb0.toInt(),0xffffcbb0.toInt()), // orange-1
  Colors(0xff592632.toInt(),0xffffceda.toInt(),0xffffceda.toInt(),0xffffceda.toInt()), // pink-1
  Colors(0xff343247.toInt(),0xffc8c4e5.toInt(),0xffc8c4e5.toInt(),0xffc8c4e5.toInt()), // purple-1
  Colors(0xff223246.toInt(),0xffbacee8.toInt(),0xffbacee8.toInt(),0xffbacee8.toInt()), // blue-1
 )
 fun colors(index:Int,isDark:Boolean):Colors=(if(isDark)dark else light)[index.coerceIn(0,ids.lastIndex)]
}
