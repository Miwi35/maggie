import { useState } from 'react'
import { MenuItemLink, useSidebarState } from 'react-admin'
import Box from '@mui/material/Box'
import List from '@mui/material/List'
import ListItemButton from '@mui/material/ListItemButton'
import ListItemIcon from '@mui/material/ListItemIcon'
import ListItemText from '@mui/material/ListItemText'
import Collapse from '@mui/material/Collapse'
import CalendarMonthIcon from '@mui/icons-material/CalendarMonth'
import StorageIcon from '@mui/icons-material/Storage'
import EventIcon from '@mui/icons-material/Event'
import DateRangeIcon from '@mui/icons-material/DateRange'
import ExpandLess from '@mui/icons-material/ExpandLess'
import ExpandMore from '@mui/icons-material/ExpandMore'

export const CustomMenu = () => {
  const [rawDataOpen, setRawDataOpen] = useState(false)
  const [agendaRawOpen, setAgendaRawOpen] = useState(false)
  const [open] = useSidebarState()

  return (
    <Box sx={{ mt: 1 }}>
      <MenuItemLink
        to="/agenda"
        primaryText="Agenda"
        leftIcon={<CalendarMonthIcon />}
      />

      {open && (
        <List component="nav" disablePadding>
          <ListItemButton onClick={() => setRawDataOpen(!rawDataOpen)}>
            <ListItemIcon sx={{ minWidth: 40 }}>
              <StorageIcon />
            </ListItemIcon>
            <ListItemText
              primary="Raw data"
              primaryTypographyProps={{ fontSize: 14, color: 'text.secondary' }}
            />
            {rawDataOpen ? <ExpandLess /> : <ExpandMore />}
          </ListItemButton>

          <Collapse in={rawDataOpen} timeout="auto" unmountOnExit>
            <List component="div" disablePadding>
              <ListItemButton
                onClick={() => setAgendaRawOpen(!agendaRawOpen)}
                sx={{ pl: 4 }}
              >
                <ListItemIcon sx={{ minWidth: 40 }}>
                  <DateRangeIcon />
                </ListItemIcon>
                <ListItemText
                  primary="Agenda"
                  primaryTypographyProps={{
                    fontSize: 14,
                    color: 'text.secondary',
                  }}
                />
                {agendaRawOpen ? <ExpandLess /> : <ExpandMore />}
              </ListItemButton>

              <Collapse in={agendaRawOpen} timeout="auto" unmountOnExit>
                <List component="div" disablePadding>
                  <MenuItemLink
                    to="/calendars"
                    primaryText="Calendars"
                    leftIcon={<DateRangeIcon />}
                    sx={{ pl: 8 }}
                  />
                  <MenuItemLink
                    to="/events"
                    primaryText="Events"
                    leftIcon={<EventIcon />}
                    sx={{ pl: 8 }}
                  />
                </List>
              </Collapse>
            </List>
          </Collapse>
        </List>
      )}
    </Box>
  )
}
