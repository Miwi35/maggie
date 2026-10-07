import { useState } from 'react'
import type { ReactElement } from 'react'
import { MenuItemLink, useSidebarState } from 'react-admin'
import Box from '@mui/material/Box'
import List from '@mui/material/List'
import ListItemButton from '@mui/material/ListItemButton'
import ListItemIcon from '@mui/material/ListItemIcon'
import ListItemText from '@mui/material/ListItemText'
import Collapse from '@mui/material/Collapse'
import CalendarMonthIcon from '@mui/icons-material/CalendarMonth'
import ChecklistIcon from '@mui/icons-material/Checklist'
import DashboardIcon from '@mui/icons-material/Dashboard'
import StorageIcon from '@mui/icons-material/Storage'
import EventIcon from '@mui/icons-material/Event'
import DateRangeIcon from '@mui/icons-material/DateRange'
import ExpandLess from '@mui/icons-material/ExpandLess'
import ExpandMore from '@mui/icons-material/ExpandMore'
import SettingsIcon from '@mui/icons-material/Settings'
import SmartToyIcon from '@mui/icons-material/SmartToy'
import CloudIcon from '@mui/icons-material/Cloud'
import RestaurantIcon from '@mui/icons-material/Restaurant'
import ShoppingCartIcon from '@mui/icons-material/ShoppingCart'
import MenuBookIcon from '@mui/icons-material/MenuBook'
import KitchenIcon from '@mui/icons-material/Kitchen'
import EggIcon from '@mui/icons-material/Egg'
import RepeatIcon from '@mui/icons-material/Repeat'
import CategoryIcon from '@mui/icons-material/Category'
import RestaurantMenuIcon from '@mui/icons-material/RestaurantMenu'
import TuneIcon from '@mui/icons-material/Tune'
import StorefrontIcon from '@mui/icons-material/Storefront'
import ReceiptLongIcon from '@mui/icons-material/ReceiptLong'
import AccountBalanceWalletIcon from '@mui/icons-material/AccountBalanceWallet'
import AccountBalanceIcon from '@mui/icons-material/AccountBalance'
import InsightsIcon from '@mui/icons-material/Insights'
import SavingsIcon from '@mui/icons-material/Savings'
import ShieldIcon from '@mui/icons-material/Shield'
import AccountBalanceOutlinedIcon from '@mui/icons-material/AccountBalanceOutlined'
import CreditScoreIcon from '@mui/icons-material/CreditScore'
import FactCheckIcon from '@mui/icons-material/FactCheck'
import UploadFileIcon from '@mui/icons-material/UploadFile'
import { ModuleIcon } from './ModuleIcon'
import type { ModuleName } from '../../theme'

const tinted = (module: ModuleName, icon: ReactElement) => <ModuleIcon module={module}>{icon}</ModuleIcon>

export const CustomMenu = () => {
  const [groceryOpen, setGroceryOpen] = useState(false)
  const [nutritionOpen, setNutritionOpen] = useState(false)
  const [financeOpen, setFinanceOpen] = useState(false)
  const [rawDataOpen, setRawDataOpen] = useState(false)
  const [agendaRawOpen, setAgendaRawOpen] = useState(false)
  const [cuisineRawOpen, setCuisineRawOpen] = useState(false)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [open] = useSidebarState()

  return (
    <Box sx={{ mt: 1 }}>
      <MenuItemLink
        to="/"
        primaryText="Tableau de bord"
        leftIcon={<DashboardIcon />}
      />
      <MenuItemLink
        to="/calendar"
        primaryText="Calendrier"
        leftIcon={<CalendarMonthIcon />}
      />
      {open && (
        <List component="nav" disablePadding>
          <ListItemButton onClick={() => setGroceryOpen(!groceryOpen)}>
            <ListItemIcon sx={{ minWidth: 40 }}>
              {tinted('cuisine', <ShoppingCartIcon />)}
            </ListItemIcon>
            <ListItemText
              primary="Courses"
              primaryTypographyProps={{ fontSize: 14, color: 'text.secondary' }}
            />
            {groceryOpen ? <ExpandLess /> : <ExpandMore />}
          </ListItemButton>

          <Collapse in={groceryOpen} timeout="auto" unmountOnExit>
            <List component="div" disablePadding>
              <MenuItemLink
                to="/grocery"
                primaryText="Liste de courses"
                leftIcon={tinted('cuisine', <ReceiptLongIcon />)}
                sx={{ pl: 4 }}
              />
              <MenuItemLink
                to="/products"
                primaryText="Produits"
                leftIcon={tinted('cuisine', <CategoryIcon />)}
                sx={{ pl: 4 }}
              />
              <MenuItemLink
                to="/stores"
                primaryText="Magasins"
                leftIcon={tinted('cuisine', <StorefrontIcon />)}
                sx={{ pl: 4 }}
              />
            </List>
          </Collapse>

          <ListItemButton onClick={() => setNutritionOpen(!nutritionOpen)}>
            <ListItemIcon sx={{ minWidth: 40 }}>
              {tinted('cuisine', <RestaurantMenuIcon />)}
            </ListItemIcon>
            <ListItemText
              primary="Nutrition"
              primaryTypographyProps={{ fontSize: 14, color: 'text.secondary' }}
            />
            {nutritionOpen ? <ExpandLess /> : <ExpandMore />}
          </ListItemButton>

          <Collapse in={nutritionOpen} timeout="auto" unmountOnExit>
            <List component="div" disablePadding>
              <MenuItemLink
                to="/recipes"
                primaryText="Recettes"
                leftIcon={tinted('cuisine', <MenuBookIcon />)}
                sx={{ pl: 4 }}
              />
              <MenuItemLink
                to="/meals"
                primaryText="Repas de la semaine"
                leftIcon={tinted('cuisine', <RestaurantIcon />)}
                sx={{ pl: 4 }}
              />
            </List>
          </Collapse>
          <ListItemButton onClick={() => setFinanceOpen(!financeOpen)}>
            <ListItemIcon sx={{ minWidth: 40 }}>
              {tinted('comptes', <AccountBalanceWalletIcon />)}
            </ListItemIcon>
            <ListItemText
              primary="Finance"
              primaryTypographyProps={{ fontSize: 14, color: 'text.secondary' }}
            />
            {financeOpen ? <ExpandLess /> : <ExpandMore />}
          </ListItemButton>

          <Collapse in={financeOpen} timeout="auto" unmountOnExit>
            <List component="div" disablePadding>
              <MenuItemLink
                to="/finance/dashboard"
                primaryText="Vue d'ensemble"
                leftIcon={tinted('comptes', <InsightsIcon />)}
                sx={{ pl: 4 }}
              />
              <MenuItemLink
                to="/finance/banks"
                primaryText="Banques"
                leftIcon={tinted('comptes', <AccountBalanceOutlinedIcon />)}
                sx={{ pl: 4 }}
              />
              <MenuItemLink
                to="/finance/import"
                primaryText="Import de relevé"
                leftIcon={tinted('comptes', <UploadFileIcon />)}
                sx={{ pl: 4 }}
              />
              <MenuItemLink
                to="/accounts"
                primaryText="Comptes"
                leftIcon={tinted('comptes', <AccountBalanceIcon />)}
                sx={{ pl: 4 }}
              />
              <MenuItemLink
                to="/categories"
                primaryText="Catégories"
                leftIcon={tinted('comptes', <CategoryIcon />)}
                sx={{ pl: 4 }}
              />
              <MenuItemLink
                to="/envelopes"
                primaryText="Budgets"
                leftIcon={tinted('comptes', <SavingsIcon />)}
                sx={{ pl: 4 }}
              />
              <MenuItemLink
                to="/finance/cushion"
                primaryText="Matelas"
                leftIcon={tinted('comptes', <ShieldIcon />)}
                sx={{ pl: 4 }}
              />
              <MenuItemLink
                to="/loans"
                primaryText="Prêts"
                leftIcon={tinted('comptes', <CreditScoreIcon />)}
                sx={{ pl: 4 }}
              />
              <MenuItemLink
                to="/finance/monthly-review"
                primaryText="Revue mensuelle"
                leftIcon={tinted('comptes', <FactCheckIcon />)}
                sx={{ pl: 4 }}
              />
            </List>
          </Collapse>

          <ListItemButton onClick={() => setRawDataOpen(!rawDataOpen)}>
            <ListItemIcon sx={{ minWidth: 40 }}>
              <StorageIcon />
            </ListItemIcon>
            <ListItemText
              primary="Données brutes"
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
                    to="/agendas"
                    primaryText="Agendas"
                    leftIcon={<DateRangeIcon />}
                    sx={{ pl: 8 }}
                  />
                  <MenuItemLink
                    to="/events"
                    primaryText="Événements"
                    leftIcon={<EventIcon />}
                    sx={{ pl: 8 }}
                  />
                  <MenuItemLink
                    to="/tasks"
                    primaryText="Tâches"
                    leftIcon={<ChecklistIcon />}
                    sx={{ pl: 8 }}
                  />
                </List>
              </Collapse>

              <ListItemButton
                onClick={() => setCuisineRawOpen(!cuisineRawOpen)}
                sx={{ pl: 4 }}
              >
                <ListItemIcon sx={{ minWidth: 40 }}>
                  {tinted('cuisine', <KitchenIcon />)}
                </ListItemIcon>
                <ListItemText
                  primary="Cuisine"
                  primaryTypographyProps={{
                    fontSize: 14,
                    color: 'text.secondary',
                  }}
                />
                {cuisineRawOpen ? <ExpandLess /> : <ExpandMore />}
              </ListItemButton>

              <Collapse in={cuisineRawOpen} timeout="auto" unmountOnExit>
                <List component="div" disablePadding>
                  <MenuItemLink
                    to="/ingredients"
                    primaryText="Ingrédients"
                    leftIcon={tinted('cuisine', <EggIcon />)}
                    sx={{ pl: 8 }}
                  />
                  <MenuItemLink
                    to="/recurring_grocery_items"
                    primaryText="Articles récurrents"
                    leftIcon={tinted('cuisine', <RepeatIcon />)}
                    sx={{ pl: 8 }}
                  />
                </List>
              </Collapse>
            </List>
          </Collapse>

          <ListItemButton onClick={() => setSettingsOpen(!settingsOpen)}>
            <ListItemIcon sx={{ minWidth: 40 }}>
              <SettingsIcon />
            </ListItemIcon>
            <ListItemText
              primary="Paramètres"
              primaryTypographyProps={{ fontSize: 14, color: 'text.secondary' }}
            />
            {settingsOpen ? <ExpandLess /> : <ExpandMore />}
          </ListItemButton>

          <Collapse in={settingsOpen} timeout="auto" unmountOnExit>
            <List component="div" disablePadding>
              <MenuItemLink
                to="/settings/preferences"
                primaryText="Préférences"
                leftIcon={<TuneIcon />}
                sx={{ pl: 4 }}
              />
              <MenuItemLink
                to="/settings/agent"
                primaryText="Agent"
                leftIcon={<SmartToyIcon />}
                sx={{ pl: 4 }}
              />
              <MenuItemLink
                to="/settings/google"
                primaryText="Google"
                leftIcon={<CloudIcon />}
                sx={{ pl: 4 }}
              />
            </List>
          </Collapse>
        </List>
      )}
    </Box>
  )
}
