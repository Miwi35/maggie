import { Layout as RALayout, LayoutProps } from 'react-admin'
import { ChatWidget } from '../chat/ChatWidget'

export const Layout = (props: LayoutProps) => (
  <>
    <RALayout {...props} />
    <ChatWidget />
  </>
)
