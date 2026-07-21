Writing a DeskLink plugin

Plugins belong to the DeskLink product surface. Public strings, log tags, and
resource names should use DeskLink. Packet type values and discovery names are
different: they are retained in
`protocol/legacykdeconnectv8/LegacyKdeConnectV8.kt` because it is retained as
an inactive historical compatibility reference. The active peer path uses
DeskLink Protocol v9.

Do not introduce new `kdeconnect.*` values for product naming. New DeskLink
protocol identifiers belong to a future versioned protocol and are not part of
this release.
