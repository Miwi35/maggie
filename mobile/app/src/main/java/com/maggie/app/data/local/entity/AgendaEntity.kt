package com.maggie.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.maggie.app.data.model.Agenda

@Entity(tableName = "agendas")
data class AgendaEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val description: String?,
    val timeZone: String,
    val color: String,
    val isDefault: Boolean,
    val googleCalendarId: String?,
) {
    fun toModel(): Agenda = Agenda(
        id = id,
        name = name,
        description = description,
        timeZone = timeZone,
        color = color,
        isDefault = isDefault,
        googleCalendarId = googleCalendarId,
    )

    companion object {
        fun fromModel(agenda: Agenda): AgendaEntity =
            AgendaEntity(
                id = agenda.id,
                name = agenda.name,
                description = agenda.description,
                timeZone = agenda.timeZone,
                color = agenda.color,
                isDefault = agenda.isDefault,
                googleCalendarId = agenda.googleCalendarId,
            )
    }
}
