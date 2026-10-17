<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Enum\PeerDebtDirection;
use Maggie\Finance\Repository\PeerDebtRepository;

/**
 * « On te doit / Tu dois »: what is left to give back each way, all contacts
 * together, and the net per person (MAG-455).
 */
class GetPeerDebtSummary
{
    public function __construct(
        private readonly PeerDebtRepository $peerDebtRepository,
    ) {
    }

    /**
     * `byContact`: `netCents` is positive when the person owes the owner,
     * negative when the owner owes them; `compensates` when they owe each other,
     * so that one transfer of the difference settles both. The biggest net first.
     *
     * @return array{owedToMeCents: int, iOweCents: int, byContact: list<array{contactId: string, name: string, netCents: int, compensates: bool}>}
     */
    public function execute(User $user): array
    {
        return [
            'owedToMeCents' => $this->peerDebtRepository->sumRemaining($user, PeerDebtDirection::OwedToMe),
            'iOweCents' => $this->peerDebtRepository->sumRemaining($user, PeerDebtDirection::IOwe),
            'byContact' => $this->byContact($user),
        ];
    }

    /** @return list<array{contactId: string, name: string, netCents: int, compensates: bool}> */
    private function byContact(User $user): array
    {
        $people = [];
        foreach ($this->peerDebtRepository->sumRemainingByContact($user) as $row) {
            $people[$row['contactId']] ??= ['contactId' => $row['contactId'], 'name' => $row['name'], 'owedToMe' => 0, 'iOwe' => 0];
            $people[$row['contactId']][PeerDebtDirection::OwedToMe->value === $row['direction'] ? 'owedToMe' : 'iOwe'] += $row['remainingCents'];
        }

        $byContact = array_map(static fn (array $person): array => [
            'contactId' => $person['contactId'],
            'name' => $person['name'],
            'netCents' => $person['owedToMe'] - $person['iOwe'],
            'compensates' => $person['owedToMe'] > 0 && $person['iOwe'] > 0,
        ], array_values($people));

        usort($byContact, static fn (array $a, array $b): int => [abs($b['netCents']), $a['name']] <=> [abs($a['netCents']), $b['name']]);

        return $byContact;
    }
}
