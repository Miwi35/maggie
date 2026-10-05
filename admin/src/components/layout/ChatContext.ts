import { createContext, useContext } from 'react'

export type SidebarTab = 'chat' | 'mind'

interface ChatContextValue {
  chatOpen: boolean
  sidebarTab: SidebarTab
  onChatToggle: () => void
  onMindToggle: () => void
  unreadChat: boolean
  onVoiceMessage: (text: string) => void
}

export const ChatContext = createContext<ChatContextValue>({
  chatOpen: false,
  sidebarTab: 'chat',
  onChatToggle: () => {},
  onMindToggle: () => {},
  unreadChat: false,
  onVoiceMessage: () => {},
})

export const useChatContext = () => useContext(ChatContext)
