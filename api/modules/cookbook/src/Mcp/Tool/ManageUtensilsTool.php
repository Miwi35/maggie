<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Mcp\Tool;

use Maggie\Cookbook\Entity\Utensil;
use Maggie\Cookbook\Message\CreateUtensilCommand;
use Maggie\Cookbook\Message\DeleteUtensilCommand;
use Maggie\Cookbook\Message\RenameUtensilCommand;
use Maggie\Cookbook\Repository\UtensilRepository;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'manage_utensils', description: 'List, create, rename, or delete the user\'s kitchen utensils (oven, Thermomix, mandoline…), the catalogue recipes say they are made with. A name is unique whatever its case: create answers the existing utensil when the name is already there. Deleting a utensil removes it from its recipes, which stay.')]
class ManageUtensilsTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly UtensilRepository $utensilRepository,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(
        string $action,
        ?string $utensilId = null,
        ?string $name = null,
    ): string {
        try {
            return match ($action) {
                'list' => $this->list(),
                'create' => $this->create($name),
                'rename' => $this->rename($utensilId, $name),
                'delete' => $this->delete($utensilId),
                default => $this->error("Unknown action: {$action}. Use list, create, rename, or delete."),
            };
        } catch (MissingMcpUserException $e) {
            return $this->error($e->getMessage());
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return $this->error($cause->getMessage());
        }
    }

    private function list(): string
    {
        $user = $this->userContext->requireUser();

        return json_encode([
            'utensils' => array_map($this->describe(...), $this->utensilRepository->findByUser($user)),
        ], JSON_THROW_ON_ERROR);
    }

    private function create(?string $name): string
    {
        if (null === $name || '' === trim($name)) {
            return $this->error('name is required for create.');
        }
        if (mb_strlen(trim($name)) > 255) {
            return $this->error('A utensil name is 255 characters at most.');
        }

        $user = $this->userContext->requireUser();

        $envelope = $this->bus->dispatch(new CreateUtensilCommand(
            userId: (string) $user->getId(),
            name: $name,
        ));

        /** @var Utensil $utensil */
        $utensil = $envelope->last(HandledStamp::class)->getResult();

        return json_encode(['success' => true, 'utensil' => $this->describe($utensil)], JSON_THROW_ON_ERROR);
    }

    private function rename(?string $utensilId, ?string $name): string
    {
        if (null === $utensilId) {
            return $this->error('utensilId is required for rename.');
        }
        if (null === $name || '' === trim($name)) {
            return $this->error('name is required for rename.');
        }
        if (mb_strlen(trim($name)) > 255) {
            return $this->error('A utensil name is 255 characters at most.');
        }

        $utensil = $this->findOwn($utensilId);
        if (null === $utensil) {
            return $this->error("Utensil not found: {$utensilId}");
        }

        $envelope = $this->bus->dispatch(new RenameUtensilCommand(
            utensilId: (string) $utensil->getId(),
            name: $name,
        ));

        /** @var Utensil $renamed */
        $renamed = $envelope->last(HandledStamp::class)->getResult();

        return json_encode(['success' => true, 'utensil' => $this->describe($renamed)], JSON_THROW_ON_ERROR);
    }

    private function delete(?string $utensilId): string
    {
        if (null === $utensilId) {
            return $this->error('utensilId is required for delete.');
        }

        $utensil = $this->findOwn($utensilId);
        if (null === $utensil) {
            return $this->error("Utensil not found: {$utensilId}");
        }

        $this->bus->dispatch(new DeleteUtensilCommand(utensilId: (string) $utensil->getId()));

        return json_encode(['success' => true], JSON_THROW_ON_ERROR);
    }

    private function findOwn(string $utensilId): ?Utensil
    {
        return $this->utensilRepository->findOneForUser($utensilId, $this->userContext->requireUser());
    }

    /** @return array{id: string, name: string} */
    private function describe(Utensil $utensil): array
    {
        return ['id' => (string) $utensil->getId(), 'name' => $utensil->getName()];
    }

    private function error(string $message): string
    {
        return json_encode(['error' => $message], JSON_THROW_ON_ERROR);
    }
}
