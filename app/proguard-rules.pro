# Paho mqttv3 is instantiated reflectively in a few places and its callbacks are
# dispatched from its own threads; keep it whole to be safe with R8.
-keep class org.eclipse.paho.client.mqttv3.** { *; }
-dontwarn org.eclipse.paho.client.mqttv3.**
