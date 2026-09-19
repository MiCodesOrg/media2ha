# Media2HA

An Android app that relays the device's active media session to Home Assistant over MQTT, and applies Home Assistant's media commands to that session.

## Language

**Active session**:
The media session currently playing, or the most recently updated one when nothing is playing.
_Avoid_: current player, foreground app

**Session snapshot**:
A point-in-time description of the active session, independent of how it was observed.
_Avoid_: state object, DTO

**Session report**:
The translation of a session snapshot into the payloads Home Assistant consumes.
_Avoid_: state sync, relay payload

**Command**:
An action Home Assistant asks the app to perform on the active session.
_Avoid_: action, request

**Discovery**:
The retained Home Assistant configuration that creates the media_player entity.
_Avoid_: registration, announcement

**device_id**:
The stable identifier that names the entity and its MQTT topics.
_Avoid_: device name, client id
