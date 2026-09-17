<?php

declare(strict_types=1);

namespace Maggie\Finance\Import;

/**
 * Reads a bank CSV export into statement rows.
 *
 * Banks agree on nothing: the separator, the date format, the decimal mark and
 * whether debits are negative or live in their own column all vary. Rather
 * than a profile per bank, the parser reads the header and works out what it
 * is looking at — a new bank usually needs no code at all.
 */
class CsvStatementParser
{
    private const DATE_COLUMNS = ['date', 'date operation', "date d'operation", 'date de valeur', 'completed date', 'started date', 'booking date', 'transaction date', 'datum'];
    private const LABEL_COLUMNS = ['libelle', 'libelle operation', 'description', 'details', 'nature', 'motif', 'reference', 'payee', 'beschreibung'];
    private const AMOUNT_COLUMNS = ['montant', 'amount', 'betrag', 'valeur'];
    private const DEBIT_COLUMNS = ['debit', 'depense', 'sortie', 'withdrawal', 'paid out'];
    private const CREDIT_COLUMNS = ['credit', 'recette', 'entree', 'deposit', 'paid in'];
    private const CURRENCY_COLUMNS = ['devise', 'currency', 'monnaie'];

    /**
     * @return array{rows: list<StatementRow>, errors: list<string>}
     */
    public function parse(string $contents, ?string $defaultCurrency = null): array
    {
        $lines = preg_split('/\R/u', $this->stripBom($contents)) ?: [];
        $lines = array_values(array_filter($lines, static fn (string $line) => trim($line) !== ''));

        if ($lines === []) {
            return ['rows' => [], 'errors' => ['The file is empty.']];
        }

        $separator = $this->detectSeparator($lines);
        $headerIndex = $this->findHeaderLine($lines, $separator);

        if ($headerIndex === null) {
            return [
                'rows' => [],
                'errors' => ['No header row found: expected a line naming at least a date and an amount column.'],
            ];
        }

        $header = $this->normaliseHeader(str_getcsv($lines[$headerIndex], $separator, '"', '\\'));
        $columns = $this->mapColumns($header);

        $rows = [];
        $errors = [];

        foreach (\array_slice($lines, $headerIndex + 1) as $offset => $line) {
            $lineNumber = $headerIndex + $offset + 2;
            $cells = str_getcsv($line, $separator, '"', '\\');

            try {
                $row = $this->toRow($cells, $columns, $lineNumber, $defaultCurrency);
            } catch (\RuntimeException $e) {
                $errors[] = sprintf('Line %d: %s', $lineNumber, $e->getMessage());
                continue;
            }

            if ($row !== null) {
                $rows[] = $row;
            }
        }

        return ['rows' => $rows, 'errors' => $errors];
    }

    /**
     * @param list<string|null>  $cells
     * @param array<string, int> $columns
     */
    private function toRow(array $cells, array $columns, int $lineNumber, ?string $defaultCurrency): ?StatementRow
    {
        $read = static function (?int $index) use ($cells): string {
            return $index === null ? '' : trim($cells[$index] ?? '');
        };

        $rawDate = $read($columns['date'] ?? null);
        if ($rawDate === '') {
            // Export footers and blank separators are not errors worth reporting.
            return null;
        }

        $bookedAt = $this->parseDate($rawDate)
            ?? throw new \RuntimeException(sprintf('cannot read the date "%s".', $rawDate));

        $amountCents = $this->readAmount($read, $columns)
            ?? throw new \RuntimeException('cannot read the amount.');

        $label = $read($columns['label'] ?? null);
        if ($label === '') {
            $label = 'Sans libellé';
        }

        $currency = strtoupper($read($columns['currency'] ?? null));
        if (!preg_match('/^[A-Z]{3}$/', $currency)) {
            $currency = $defaultCurrency ?? 'EUR';
        }

        return new StatementRow(
            bookedAt: $bookedAt,
            label: preg_replace('/\s+/u', ' ', $label) ?? $label,
            amountCents: $amountCents,
            currency: $currency,
            lineNumber: $lineNumber,
        );
    }

    /**
     * @param callable(?int): string $read
     * @param array<string, int>     $columns
     */
    private function readAmount(callable $read, array $columns): ?int
    {
        if (isset($columns['amount'])) {
            $amount = $this->parseAmount($read($columns['amount']));

            return $amount === null ? null : $amount;
        }

        // Separate debit and credit columns: exactly one of them is filled.
        $debit = $this->parseAmount($read($columns['debit'] ?? null));
        $credit = $this->parseAmount($read($columns['credit'] ?? null));

        if ($debit !== null && $debit !== 0) {
            return -abs($debit);
        }

        if ($credit !== null && $credit !== 0) {
            return abs($credit);
        }

        return null;
    }

    /** Cents from "1 234,56", "-1,234.56", "1234.56 €" and their friends. */
    private function parseAmount(string $raw): ?int
    {
        $raw = trim(str_replace(["\u{00A0}", "\u{202F}", ' ', '€', 'CHF', 'EUR', '$'], '', $raw));
        if ($raw === '' || $raw === '-') {
            return null;
        }

        $negative = str_starts_with($raw, '-') || (str_starts_with($raw, '(') && str_ends_with($raw, ')'));
        $raw = trim($raw, '-()+');

        // Whichever of , or . comes last is the decimal mark; the other groups digits.
        $lastComma = strrpos($raw, ',');
        $lastDot = strrpos($raw, '.');

        if ($lastComma !== false && $lastDot !== false) {
            $decimal = $lastComma > $lastDot ? ',' : '.';
            $raw = str_replace($decimal === ',' ? '.' : ',', '', $raw);
            $raw = str_replace($decimal, '.', $raw);
        } elseif ($lastComma !== false) {
            // A lone comma is decimal unless it groups thousands: "1,234".
            $decimals = \strlen($raw) - $lastComma - 1;
            $raw = $decimals === 3 ? str_replace(',', '', $raw) : str_replace(',', '.', $raw);
        }

        if (!is_numeric($raw)) {
            return null;
        }

        $cents = (int) round((float) $raw * 100);

        return $negative ? -$cents : $cents;
    }

    private function parseDate(string $raw): ?\DateTimeImmutable
    {
        $raw = trim($raw);

        foreach (['d/m/Y', 'd-m-Y', 'd.m.Y', 'Y-m-d', 'Y/m/d', 'd/m/y', 'Y-m-d H:i:s', 'd/m/Y H:i', 'Y-m-d\TH:i:s'] as $format) {
            $date = \DateTimeImmutable::createFromFormat($format, $raw);
            if ($date !== false && $date->format($format) === $raw) {
                return $date->setTime(0, 0);
            }
        }

        // Anything else PHP understands, as a last resort.
        try {
            return (new \DateTimeImmutable($raw))->setTime(0, 0);
        } catch (\Exception) {
            return null;
        }
    }

    /**
     * @param list<string> $header
     *
     * @return array<string, int>
     */
    private function mapColumns(array $header): array
    {
        $columns = [];

        foreach ($header as $index => $name) {
            foreach ([
                'date' => self::DATE_COLUMNS,
                'label' => self::LABEL_COLUMNS,
                'amount' => self::AMOUNT_COLUMNS,
                'debit' => self::DEBIT_COLUMNS,
                'credit' => self::CREDIT_COLUMNS,
                'currency' => self::CURRENCY_COLUMNS,
            ] as $role => $candidates) {
                if (isset($columns[$role])) {
                    continue;
                }
                if ($this->matches($name, $candidates)) {
                    $columns[$role] = $index;
                }
            }
        }

        return $columns;
    }

    /** @param list<string> $candidates */
    private function matches(string $name, array $candidates): bool
    {
        foreach ($candidates as $candidate) {
            if ($name === $candidate || str_starts_with($name, $candidate)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Exports often open with a few lines of account details before the real
     * header, so the header is the first line that names a date and a way to
     * read an amount.
     *
     * @param list<string> $lines
     */
    private function findHeaderLine(array $lines, string $separator): ?int
    {
        foreach (\array_slice($lines, 0, 20) as $index => $line) {
            $columns = $this->mapColumns($this->normaliseHeader(str_getcsv($line, $separator, '"', '\\')));

            if (isset($columns['date'])
                && (isset($columns['amount']) || isset($columns['debit']) || isset($columns['credit']))
            ) {
                return $index;
            }
        }

        return null;
    }

    /**
     * @param list<string|null> $header
     *
     * @return list<string>
     */
    private function normaliseHeader(array $header): array
    {
        return array_map(static function (?string $name): string {
            $name = mb_strtolower(trim((string) $name));
            $name = strtr($name, [
                'é' => 'e', 'è' => 'e', 'ê' => 'e', 'ë' => 'e',
                'à' => 'a', 'â' => 'a', 'î' => 'i', 'ï' => 'i',
                'ô' => 'o', 'ö' => 'o', 'ù' => 'u', 'û' => 'u', 'ü' => 'u', 'ç' => 'c',
            ]);

            return preg_replace('/[^a-z0-9 ]+/', ' ', $name) ?? $name;
        }, $header);
    }

    /** @param list<string> $lines */
    private function detectSeparator(array $lines): string
    {
        $candidates = [';' => 0, ',' => 0, "\t" => 0, '|' => 0];

        foreach (\array_slice($lines, 0, 10) as $line) {
            foreach (array_keys($candidates) as $separator) {
                $candidates[$separator] += substr_count($line, $separator);
            }
        }

        arsort($candidates);

        return array_key_first($candidates);
    }

    private function stripBom(string $contents): string
    {
        return str_starts_with($contents, "\u{FEFF}") ? substr($contents, 3) : $contents;
    }
}
