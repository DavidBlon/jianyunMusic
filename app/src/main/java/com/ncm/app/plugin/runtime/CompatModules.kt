package com.ncm.app.plugin.runtime

/** 受控 CommonJS 兼容模块表（spec §6.3）。未在此表的 require 一律拒绝。 */
val COMPAT_MODULE_NAMES: Set<String> = setOf(
    "axios", "crypto-js", "qs", "big-integer", "dayjs", "cheerio", "he"
)
