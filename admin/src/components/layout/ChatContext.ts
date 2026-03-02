import { createContext, useContext } from 'react'

export type SidebarTab = 'chat' | 'mind'

interface ChatContextValue {
  chatOpen: boolean
  sidebarTab: SidebarTab
  onChatToggle: () => void
  onMindToggle: () => void
  unreadChat: boolean
  onVoiceMessage: (text: string) => void
  wakeWordEnabled: boolean
  wakeWordListening: boolean
  wakeWordTriggered: boolean
  toggleWakeWord: (value?: boolean) => void
  pauseWakeWord: () => void
  resumeWakeWord: () => void
  clearWakeWordTrigger: () => void
}

export const ChatContext = createContext<ChatContextValue>({
  chatOpen: false,
  sidebarTab: 'chat',
  onChatToggle: () => {},
  onMindToggle: () => {},
  unreadChat: false,
  onVoiceMessage: () => {},
  wakeWordEnabled: false,
  wakeWordListening: false,
  wakeWordTriggered: false,
  toggleWakeWord: () => {},
  pauseWakeWord: () => {},
  resumeWakeWord: () => {},
  clearWakeWordTrigger: () => {},
})

export const useChatContext = () => useContext(ChatContext)
