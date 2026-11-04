package com.openautolink.app.diagnostics.live

import java.net.URI

/** Canonical origin + base path is an authentication namespace. LAN HTTPS is intentional. */
internal class LiveEndpoint private constructor(val base:String) {
    override fun equals(other:Any?)=other is LiveEndpoint && base==other.base
    override fun hashCode()=base.hashCode()
    override fun toString()="LiveEndpoint" // keep user configuration out of incidental diagnostics

    companion object {
        val LEGACY=parse("https://mossy-access.themosscrew.com/oal")
        fun parse(text:String):LiveEndpoint {
            require(text.length<=2048 && text.none { it.isISOControl() || it=='\\' })
            val uri=URI(text.trim())
            require(uri.scheme.equals("https",true) && uri.host!=null && uri.rawUserInfo==null)
            require(uri.rawQuery==null && uri.rawFragment==null && uri.port in -1..65535 && uri.port!=0)
            val path=uri.rawPath.orEmpty().trimEnd('/')
            // No encoded separators/dot-segments, ambiguous proxy traversal or request injection.
            require(path.split('/').none { it=="." || it==".." } && !path.contains("//"))
            require(!Regex("%(?:2f|5c|2e|0[0-9a-f]|1[0-9a-f]|7f|25)",RegexOption.IGNORE_CASE).containsMatchIn(path))
            val host=uri.host.lowercase()
            val port=if(uri.port==-1 || uri.port==443) "" else ":${uri.port}"
            return LiveEndpoint("https://$host$port$path")
        }
    }
}
