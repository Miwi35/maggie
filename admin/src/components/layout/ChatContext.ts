import { createContext, useContext } from 'react'

interface ChatContextValue {
  chatOpen: boolean
  onChatToggle: () => void
  unreadChat: boolean
  onVoiceMessage: (text: string) => void
}

export const ChatContext = createContext<ChatContextValue>({
  chatOpen: false,
  onChatToggle: () => {},
  unreadChat: false,
  onVoiceMessage: () => {},
})

export const useChatContext = () => useContext(ChatContext)
