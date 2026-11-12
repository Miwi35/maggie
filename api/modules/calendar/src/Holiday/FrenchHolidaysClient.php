<?php

declare(strict_types=1);

namespace Maggie\Calendar\Holiday;

use Symfony\Contracts\HttpClient\Exception\ExceptionInterface;
use Symfony\Contracts\HttpClient\HttpClientInterface;

/**
 * The public holidays of metropolitan France (MAG-810), from the State's own
 * API: free and keyless, `{base}/{year}.json` answers `{"2027-01-01": "1er janvier", …}`.
 */
class FrenchHolidaysClient
{
    private const TIMEOUT_SECONDS = 10;

    public function __construct(
        private readonly HttpClientInterface $httpClient,
        private readonly string $frenchHolidaysUrl,
    ) {
    }

    /**
     * @return array<string, string> the day, YYYY-MM-DD, to its name, by date
     *
     * @throws HolidaySourceException
     */
    public function forYear(int $year): array
    {
        try {
            $data = $this->httpClient
                ->request('GET', \sprintf('%s/%d.json', rtrim($this->frenchHolidaysUrl, '/'), $year), ['timeout' => self::TIMEOUT_SECONDS])
                ->toArray();
        } catch (ExceptionInterface $e) {
            throw new HolidaySourceException('The French holidays API is unreachable or answered with an error.', 0, $e);
        }

        // Checked whole before anything is returned: half a year is never imported.
        $days = [];
        foreach ($data as $day => $name) {
            $day = (string) $day;
            $parsed = \DateTimeImmutable::createFromFormat('!Y-m-d', $day);
            if (false === $parsed || $parsed->format('Y-m-d') !== $day || (int) $parsed->format('Y') !== $year || !\is_string($name) || '' === trim($name)) {
                throw new HolidaySourceException(\sprintf('The French holidays API answered an unexpected entry for %d.', $year));
            }
            $days[$day] = trim($name);
        }
        if ([] === $days) {
            throw new HolidaySourceException(\sprintf('The French holidays API answered no holiday for %d.', $year));
        }
        ksort($days);

        return $days;
    }
}
