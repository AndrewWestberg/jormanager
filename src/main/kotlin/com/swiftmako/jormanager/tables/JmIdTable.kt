package com.swiftmako.jormanager.tables

import org.jetbrains.exposed.dao.id.IdTable

abstract class JmLongIdTable(name: String = "", columnName: String = "id") : IdTable<Long>(name) {
    override val id = long(columnName).autoIncrement(idSeqName = "hibernate_sequence").entityId()
    override val primaryKey by lazy { PrimaryKey(id) }
}