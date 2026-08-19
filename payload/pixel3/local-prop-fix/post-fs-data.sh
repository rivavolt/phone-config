#!/system/bin/sh
# userdebug/eng leak (RASP probes the userdebug_or_eng_prop label)
resetprop -n ro.build.type user
resetprop -n ro.build.tags release-keys
resetprop -n ro.build.flavor blueline-user
resetprop -n ro.build.display.id AP4A.250105.002
# custom-ROM fingerprint: delete the whole ro.lineage.* family + modversion
resetprop -d ro.lineage.build.version
resetprop -d ro.lineage.device
resetprop -d ro.lineage.display.version
resetprop -d ro.lineage.releasetype
resetprop -d ro.lineage.version
resetprop -d ro.lineagelegal.url
resetprop -d ro.modversion
