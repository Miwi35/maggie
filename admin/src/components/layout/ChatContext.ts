import { createContext, useContext } from 'react'

interface ChatContextValue {
  chatOpen: boolean
  onChatToggle: () => void
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
  onChatToggle: () => {},
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
