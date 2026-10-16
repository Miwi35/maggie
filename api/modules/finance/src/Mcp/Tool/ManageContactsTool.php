<?php

declare(strict_types=1);

namespace Maggie\Finance\Mcp\Tool;

use Maggie\Core\Entity\User;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\Contact\CounterpartyMatch;
use Maggie\Finance\Entity\Contact;
use Maggie\Finance\Entity\ContactCounterparty;
use Maggie\Finance\Message\CreateContactCommand;
use Maggie\Finance\Message\DeleteContactCommand;
use Maggie\Finance\Message\LinkContactCounterpartyCommand;
use Maggie\Finance\Message\UnlinkContactCounterpartyCommand;
use Maggie\Finance\Message\UpdateContactCommand;
use Maggie\Finance\Repository\ContactCounterpartyRepository;
use Maggie\Finance\Repository\ContactRepository;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\UseCase\MeasureCounterpartyUsage;
use Maggie\Finance\UseCase\SuggestContactCounterparties;
use Mcp\Capability\Attribute\McpTool;
use Mcp\Capability\Attribute\Schema;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;
use Symfony\Component\Uid\Ulid;

#[McpTool(name: 'manage_contacts', description: 'The people the owner lends money to or borrows from — one list for both — and the labels under which each one appears on the bank statements (counterparties, e.g. « VIR. DE TOM MARTIN »). Actions: list (every contact with its counterparties), search (by name, query), create (name, optional notes), update (contactId; only provided fields change; to empty notes, list it in clear), delete (contactId; their transactions are detached), link_counterparty (contactId and either label or transactionId — the label of that transaction is used; every non-internal transaction it recognises, past and future, is attached to the contact; a label belongs to one contact only), unlink_counterparty (counterpartyId; its transactions are detached), suggest_counterparties (contactId; labels of unattached transactions that contain a word of the name, most frequent first — confirm them with link_counterparty). A transfer between the owner\'s own accounts is never attached to a contact.')]
class ManageContactsTool
{
    private const ACTIONS = ['list', 'search', 'create', 'update', 'delete', 'link_counterparty', 'unlink_counterparty', 'suggest_counterparties'];

    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly ContactRepository $contactRepository,
        private readonly ContactCounterpartyRepository $counterpartyRepository,
        private readonly TransactionRepository $transactionRepository,
        private readonly MeasureCounterpartyUsage $usage,
        private readonly SuggestContactCounterparties $suggest,
        private readonly McpUserContext $userContext,
    ) {
    }

    /** @param list<string>|null $clear */
    public function __invoke(
        #[Schema(enum: self::ACTIONS)]
        string $action,
        ?string $contactId = null,
        ?string $name = null,
        ?string $notes = null,
        ?string $query = null,
        ?string $label = null,
        ?string $transactionId = null,
        ?string $counterpartyId = null,
        ?array $clear = null,
    ): string {
        try {
            return match ($action) {
                'list' => $this->list(),
                'search' => $this->search($query),
                'create' => $this->create($name, $notes),
                'update' => $this->update($contactId, $name, $notes, $clear),
                'delete' => $this->delete($contactId),
                'link_counterparty' => $this->link($contactId, $label, $transactionId),
                'unlink_counterparty' => $this->unlink($counterpartyId),
                'suggest_counterparties' => $this->suggestions($contactId),
                default => self::error("Unknown action: {$action}. Use list, search, create, update, delete, link_counterparty, unlink_counterparty or suggest_counterparties."),
            };
        } catch (MissingMcpUserException|\DomainException $e) {
            return self::error($e->getMessage());
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return self::error($cause->getMessage());
        }
    }

    private function list(): string
    {
        $user = $this->userContext->requireUser();

        return self::json(['contacts' => $this->serializeAll($user, $this->contactRepository->findByUser($user))]);
    }

    private function search(?string $query): string
    {
        if (null === $query || '' === trim($query)) {
            return self::error('query is required for search.');
        }
        $user = $this->userContext->requireUser();

        return self::json(['contacts' => $this->serializeAll($user, $this->contactRepository->searchByUser($user, $query))]);
    }

    private function create(?string $name, ?string $notes): string
    {
        if (null === $name) {
            return self::error('name is required for create.');
        }

        $stamped = $this->bus->dispatch(new CreateContactCommand(
            userId: (string) $this->userContext->requireUser()->getId(),
            name: $name,
            notes: $notes,
        ));
        /** @var Contact $contact */
        $contact = $stamped->last(HandledStamp::class)->getResult();

        return self::json(['success' => true, 'contact' => $this->serialize($contact, [])]);
    }

    /** @param list<string>|null $clear */
    private function update(?string $contactId, ?string $name, ?string $notes, ?array $clear): string
    {
        if (null === $contactId) {
            return self::error('contactId is required for update.');
        }
        $user = $this->userContext->requireUser();

        $stamped = $this->bus->dispatch(new UpdateContactCommand(
            userId: (string) $user->getId(),
            contactId: $contactId,
            name: $name,
            notes: $notes,
            clearFields: array_values(array_intersect($clear ?? [], ['notes'])),
        ));
        /** @var Contact $contact */
        $contact = $stamped->last(HandledStamp::class)->getResult();

        return self::json(['success' => true, 'contact' => $this->serializeAll($user, [$contact])[0]]);
    }

    private function delete(?string $contactId): string
    {
        if (null === $contactId) {
            return self::error('contactId is required for delete.');
        }

        $this->bus->dispatch(new DeleteContactCommand(
            userId: (string) $this->userContext->requireUser()->getId(),
            contactId: $contactId,
        ));

        return self::json(['success' => true]);
    }

    private function link(?string $contactId, ?string $label, ?string $transactionId): string
    {
        if (null === $contactId || (null === $label) === (null === $transactionId)) {
            return self::error('contactId and either label or transactionId are required for link_counterparty.');
        }
        $user = $this->userContext->requireUser();

        if (null !== $transactionId) {
            $transaction = Ulid::isValid($transactionId)
                ? $this->transactionRepository->findOneBy(['id' => $transactionId, 'user' => $user])
                : null;
            if (null === $transaction) {
                return self::error("Transaction not found: {$transactionId}");
            }
            $label = CounterpartyMatch::labelFor($transaction);
        }

        $stamped = $this->bus->dispatch(new LinkContactCounterpartyCommand(
            userId: (string) $user->getId(),
            contactId: $contactId,
            label: (string) $label,
        ));
        /** @var ContactCounterparty $counterparty */
        $counterparty = $stamped->last(HandledStamp::class)->getResult();
        $this->usage->fill([$counterparty]);

        return self::json(['success' => true, 'counterparty' => self::serializeCounterparty($counterparty)]);
    }

    private function unlink(?string $counterpartyId): string
    {
        if (null === $counterpartyId) {
            return self::error('counterpartyId is required for unlink_counterparty.');
        }

        $this->bus->dispatch(new UnlinkContactCounterpartyCommand(
            userId: (string) $this->userContext->requireUser()->getId(),
            counterpartyId: $counterpartyId,
        ));

        return self::json(['success' => true]);
    }

    private function suggestions(?string $contactId): string
    {
        if (null === $contactId) {
            return self::error('contactId is required for suggest_counterparties.');
        }
        $user = $this->userContext->requireUser();
        $contact = Ulid::isValid($contactId) ? $this->contactRepository->findOneOwned($contactId, $user) : null;
        if (null === $contact) {
            return self::error("Contact not found: {$contactId}");
        }

        return self::json(['contactId' => $contactId, 'suggestions' => $this->suggest->execute($contact)]);
    }

    /**
     * @param Contact[] $contacts
     *
     * @return list<array<string, mixed>>
     */
    private function serializeAll(User $user, array $contacts): array
    {
        $byContact = [];
        $counterparties = $this->counterpartyRepository->findByUser($user);
        $this->usage->fill($counterparties);
        foreach ($counterparties as $counterparty) {
            $byContact[(string) $counterparty->getContact()->getId()][] = $counterparty;
        }

        return array_map(
            fn (Contact $contact): array => $this->serialize($contact, $byContact[(string) $contact->getId()] ?? []),
            array_values($contacts),
        );
    }

    /**
     * @param ContactCounterparty[] $counterparties
     *
     * @return array<string, mixed>
     */
    private function serialize(Contact $contact, array $counterparties): array
    {
        return [
            'id' => (string) $contact->getId(),
            'name' => $contact->getName(),
            'notes' => $contact->getNotes(),
            'counterparties' => array_map(self::serializeCounterparty(...), $counterparties),
        ];
    }

    /** @return array<string, mixed> */
    private static function serializeCounterparty(ContactCounterparty $counterparty): array
    {
        return [
            'id' => (string) $counterparty->getId(),
            'contactId' => (string) $counterparty->getContact()->getId(),
            'label' => $counterparty->getLabel(),
            'matchKey' => $counterparty->getMatchKey(),
            'transactionCount' => $counterparty->getTransactionCount(),
            'lastSeenOn' => $counterparty->getLastSeenOn(),
        ];
    }

    /** @param array<string, mixed> $data */
    private static function json(array $data): string
    {
        return json_encode($data, JSON_THROW_ON_ERROR);
    }

    private static function error(string $message): string
    {
        return self::json(['error' => $message]);
    }
}
