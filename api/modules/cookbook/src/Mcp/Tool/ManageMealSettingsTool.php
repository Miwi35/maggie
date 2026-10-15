<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Mcp\Tool;

use Maggie\Cookbook\Message\UpdateMealSettingsCommand;
use Maggie\Cookbook\UseCase\GetMealSettings;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;

#[McpTool(name: 'manage_meal_settings', description: 'Read or change the meal settings. defaultGuests is how many people a meal is cooked for when it names no guests of its own (1 or more, 2 until the user sets it). Action get returns the settings; action update sets defaultGuests, which is required, and rescales on the grocery list what the meals still to come that follow the default had put there — a meal with its own guests keeps them.')]
class ManageMealSettingsTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly GetMealSettings $getMealSettings,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(string $action, ?int $defaultGuests = null): string
    {
        try {
            return match ($action) {
                'get' => $this->get(),
                'update' => $this->update($defaultGuests),
                default => json_encode(['error' => "Unknown action: {$action}. Use get or update."], JSON_THROW_ON_ERROR),
            };
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            return json_encode(['error' => ($e->getPrevious() ?? $e)->getMessage()], JSON_THROW_ON_ERROR);
        }
    }

    private function get(): string
    {
        return json_encode(
            GetMealSettings::describe($this->getMealSettings->forUser($this->userContext->requireUser())),
            JSON_THROW_ON_ERROR,
        );
    }

    private function update(?int $defaultGuests): string
    {
        if (null === $defaultGuests) {
            return json_encode(['error' => 'update needs defaultGuests, a whole number of at least 1.'], JSON_THROW_ON_ERROR);
        }
        if ($defaultGuests < 1) {
            return json_encode(['error' => 'defaultGuests must be a whole number of at least 1.'], JSON_THROW_ON_ERROR);
        }

        $user = $this->userContext->requireUser();
        $settings = $this->getMealSettings->forUser($user);

        $this->bus->dispatch(new UpdateMealSettingsCommand(
            userId: (string) $user->getId(),
            mealSettingsId: (string) $settings->getId(),
            defaultGuests: $defaultGuests,
        ));

        return json_encode(
            ['success' => true] + GetMealSettings::describe($this->getMealSettings->forUser($user)),
            JSON_THROW_ON_ERROR,
        );
    }
}
