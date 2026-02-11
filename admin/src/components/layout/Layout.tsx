import { Layout as RALayout, LayoutProps } from 'react-admin'
import { ChatWidget } from '../chat/ChatWidget'
import { CustomMenu } from './Menu'

export const Layout = (props: LayoutProps) => (
  <>
    <RALayout {...props} menu={CustomMenu} />
    <ChatWidget />
  </>
)
