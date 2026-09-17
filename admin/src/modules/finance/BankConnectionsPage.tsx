import { useEffect, useState } from "react";
import Alert from "@mui/material/Alert";
import Autocomplete from "@mui/material/Autocomplete";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Card from "@mui/material/Card";
import CardContent from "@mui/material/CardContent";
import Chip from "@mui/material/Chip";
import LinearProgress from "@mui/material/LinearProgress";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { Title, useNotify } from "react-admin";
import { useSearchParams } from "react-router-dom";
import { Placeholder } from "../../components/list/ListEmpty";
import { FormSection } from "../../components/form/FormSection";
import {
  CONNECTION_STATUS_LABELS,
  expiryNotice,
  useBankConnections,
} from "./useBankConnections";
import type { BankConnection } from "./useBankConnections";

/** What the callback told us on the way back from the bank. */
const OUTCOMES: Record<
  string,
  { severity: "success" | "warning" | "error"; message: string }
> = {
  connected: { severity: "success", message: "Banque connectée." },
  refused: {
    severity: "warning",
    message: "L'accès a été refusé chez la banque. Rien n'a été connecté.",
  },
  incomplete: {
    severity: "warning",
    message: "La banque a répondu sans autorisation exploitable.",
  },
  unknown: {
    severity: "error",
    message: "Cette autorisation ne correspond à aucune demande en cours.",
  },
  failed: {
    severity: "error",
    message: "La connexion n'a pas pu être finalisée.",
  },
};

const statusColor = (
  connection: BankConnection,
): "success" | "warning" | "default" => {
  if (connection.needsReconnecting) {
    return "warning";
  }

  return connection.status === "active" ? "success" : "default";
};

const ConnectionRow = ({
  connection,
  onReconnect,
  onForget,
  busy,
}: {
  connection: BankConnection;
  onReconnect: (connection: BankConnection) => void;
  onForget: (connection: BankConnection) => void;
  busy: boolean;
}) => {
  const notice = expiryNotice(connection);
  // A pending journey and an expired consent both end in the same place: back
  // at the bank. Only the wording differs.
  const needsAction =
    connection.status === "pending" || connection.needsReconnecting;

  return (
    <Stack
      direction="row"
      spacing={2}
      alignItems="center"
      sx={{
        py: 1.5,
        borderBottom: "1px solid",
        borderColor: "divider",
        flexWrap: "wrap",
      }}
    >
      <Box sx={{ flexGrow: 1, minWidth: 220 }}>
        <Typography variant="body1">{connection.bankName}</Typography>
        <Typography variant="caption" color="text.secondary">
          {connection.lastSyncedAt
            ? `Dernière synchronisation : ${new Date(connection.lastSyncedAt).toLocaleString("fr-FR")}`
            : "Jamais synchronisée"}
        </Typography>
        {notice && (
          <Typography
            variant="caption"
            color={
              connection.needsReconnecting ? "warning.main" : "text.secondary"
            }
            sx={{ display: "block" }}
          >
            {notice}
          </Typography>
        )}
      </Box>
      <Stack direction="row" spacing={1} alignItems="center">
        <Chip
          size="small"
          color={statusColor(connection)}
          label={
            CONNECTION_STATUS_LABELS[connection.status] ?? connection.status
          }
        />
        <Button
          size="small"
          variant={needsAction ? "contained" : "text"}
          disabled={busy}
          onClick={() => onReconnect(connection)}
        >
          {connection.status === "pending" ? "Reprendre" : "Reconnecter"}
        </Button>
        <Button
          size="small"
          color="inherit"
          disabled={busy}
          onClick={() => onForget(connection)}
        >
          Supprimer
        </Button>
      </Stack>
    </Stack>
  );
};

export const BankConnectionsPage = () => {
  const {
    connections,
    banks,
    loading,
    banksError,
    refresh,
    loadBanks,
    connect,
    reconnect,
    forget,
    sync,
  } = useBankConnections();
  const [searchParams, setSearchParams] = useSearchParams();
  const [country, setCountry] = useState("FR");
  const [bankName, setBankName] = useState<string | null>(null);
  const [connecting, setConnecting] = useState(false);
  const [syncing, setSyncing] = useState(false);
  const [busyId, setBusyId] = useState<string | null>(null);
  const notify = useNotify();

  const outcome = searchParams.get("outcome");

  useEffect(() => {
    loadBanks(country);
  }, [loadBanks, country]);

  const onConnect = async () => {
    if (bankName === null) {
      return;
    }
    setConnecting(true);
    const url = await connect(bankName, country);
    setConnecting(false);

    if (url === null) {
      notify("La connexion n'a pas pu être ouverte", { type: "error" });

      return;
    }

    // The consent happens at the bank, so we hand the browser over.
    window.location.assign(url);
  };

  const onReconnect = async (connection: BankConnection) => {
    setBusyId(connection.id);
    const url = await reconnect(connection.id);
    setBusyId(null);

    if (url === null) {
      notify("La connexion n'a pas pu être rouverte", { type: "error" });

      return;
    }

    window.location.assign(url);
  };

  const onForget = async (connection: BankConnection) => {
    if (
      !window.confirm(
        `Supprimer la connexion à ${connection.bankName} ? Les comptes et leurs opérations sont conservés, ils cessent simplement de se synchroniser.`,
      )
    ) {
      return;
    }

    setBusyId(connection.id);
    const removed = await forget(connection.id);
    setBusyId(null);

    notify(
      removed
        ? "Connexion supprimée."
        : "La connexion n'a pas pu être supprimée",
      {
        type: removed ? "info" : "error",
      },
    );
  };

  const onSync = async () => {
    setSyncing(true);
    const result = await sync();
    setSyncing(false);

    if (result === null) {
      notify("La synchronisation a échoué", { type: "error" });

      return;
    }

    const refused = result.accounts.find(
      (account) => account.status === "rate_limited",
    );
    if (refused) {
      notify(
        refused.message ??
          "La banque a refusé une récupération de plus pour le moment.",
        {
          type: "warning",
        },
      );

      return;
    }

    notify(
      result.imported > 0
        ? `${result.imported} opération(s) importée(s), ${result.skipped} déjà présente(s).`
        : "Aucune nouvelle opération.",
      { type: "info" },
    );
  };

  return (
    <>
      <Title title="Banques" />

      {outcome && OUTCOMES[outcome] && (
        <Alert
          severity={OUTCOMES[outcome].severity}
          sx={{ mb: 2 }}
          onClose={() => {
            setSearchParams({});
            refresh();
          }}
        >
          {OUTCOMES[outcome].message}
          {outcome === "connected" && searchParams.get("accounts") && (
            <> {searchParams.get("accounts")} compte(s) rattaché(s).</>
          )}
        </Alert>
      )}

      <Card sx={{ mb: 2 }}>
        <CardContent>
          <FormSection
            first
            title="Connecter une banque"
            description="Vous serez redirigé vers votre banque pour autoriser l'accès. Maggie lit les opérations, elle ne peut rien déclencher sur vos comptes."
          />

          {banksError && (
            <Alert severity="warning" sx={{ mb: 2 }}>
              {banksError}
            </Alert>
          )}

          <Stack
            direction={{ xs: "column", sm: "row" }}
            spacing={2}
            alignItems="flex-start"
          >
            <TextField
              select
              size="small"
              label="Pays"
              value={country}
              onChange={(e) => setCountry(e.target.value)}
              SelectProps={{ native: true }}
              sx={{ width: 120 }}
            >
              <option value="FR">France</option>
              <option value="DE">Allemagne</option>
              <option value="BE">Belgique</option>
              <option value="ES">Espagne</option>
              <option value="IT">Italie</option>
            </TextField>

            <Autocomplete
              options={banks.map((bank) => bank.name)}
              value={bankName}
              onChange={(_, value) => setBankName(value)}
              sx={{ flexGrow: 1, minWidth: 260 }}
              renderInput={(params) => (
                <TextField
                  {...params}
                  size="small"
                  label="Banque"
                  helperText="Pour le Crédit Agricole, choisissez votre caisse régionale."
                />
              )}
            />

            <Button
              variant="contained"
              onClick={onConnect}
              disabled={bankName === null || connecting}
              sx={{ flexShrink: 0 }}
            >
              Connecter
            </Button>
          </Stack>
        </CardContent>
      </Card>

      <Card>
        <CardContent>
          <Stack direction="row" alignItems="center" sx={{ mb: 1 }}>
            <Typography
              variant="subtitle2"
              sx={{ fontWeight: 600, flexGrow: 1 }}
            >
              Banques connectées
            </Typography>
            {connections.length > 0 && (
              <Button size="small" onClick={onSync} disabled={syncing}>
                {syncing ? "Récupération…" : "Récupérer les opérations"}
              </Button>
            )}
          </Stack>

          {loading && connections.length === 0 && <LinearProgress />}

          {!loading && connections.length === 0 && (
            <Placeholder
              title="Aucune banque connectée"
              description="Connectez une banque pour que vos opérations arrivent toutes seules, sans import de fichier."
            />
          )}

          {connections.map((connection) => (
            <ConnectionRow
              key={connection.id}
              connection={connection}
              onReconnect={onReconnect}
              onForget={onForget}
              busy={busyId === connection.id}
            />
          ))}
        </CardContent>
      </Card>
    </>
  );
};
