# proguard-rules.pro
# Magic Sushi — ProGuard / R8 规则
# 当前 release 构建未开启 minify（isMinifyEnabled = false），本文件为占位
# 后续若开启混淆/缩减，请在此添加：
#   - keep class top.windyvalley.magicsushi.engine.** { *; }  # 核心逻辑层
#   - keep class top.windyvalley.magicsushi.data.** { *; }    # 数据层
