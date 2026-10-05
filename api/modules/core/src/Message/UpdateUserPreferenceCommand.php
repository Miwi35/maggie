<?php

namespace Maggie\Core\Message;

final readonly class UpdateUserPreferenceCommand
{
    /**
     * @param list<string>|null $enabledAgendaIds
     */
    public function __construct(
        public string $userPreferenceId,
        public ?string $theme = null,
        public ?string $locale = null,
        public ?string $timezone = null,
        public ?string $defaultCalendarView = null,
        public ?array $enabledAgendaIds = null,
        public ?bool $notificationsEnabled = null,
        // An empty string clears the city; null leaves it as it is.
        public ?string $defaultCity = null,
    ) {
    }
}
