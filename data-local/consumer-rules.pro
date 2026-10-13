# Consumer R8 rules for :data-local.
# Room (generated DAOs/database), Hilt, DataStore and kotlinx-serialization (serializers of @Serializable classes such
# as AppSettings) ship their own consumer rules. The ledger stores enums by Enum.name and reads them with valueOf,
# both of which survive R8 (names are string literals), and no class is accessed reflectively, so no
# module-specific rule is required.
