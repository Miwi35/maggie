package com.maggie.app.voice

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent

object AssistantRoleHelper {

    fun isAssistantRoleAvailable(context: Context): Boolean {
        val roleManager = context.getSystemService(RoleManager::class.java)
        return roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)
    }

    fun isAssistantRoleHeld(context: Context): Boolean {
        val roleManager = context.getSystemService(RoleManager::class.java)
        return roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
    }

    fun createRoleRequestIntent(context: Context): Intent {
        val roleManager = context.getSystemService(RoleManager::class.java)
        return roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
    }
}
