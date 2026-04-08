from app.models.schemas import EnergyPoint, Section


def detect_sections(
    energy_curve: list[EnergyPoint], duration: float
) -> list[Section]:
    """Detect musical sections based on energy profile changes."""
    if not energy_curve:
        return [Section(start=0.0, end=duration, type="unknown", energy="medium")]

    energies = [p.energy for p in energy_curve]
    times = [p.time for p in energy_curve]

    boundaries = _find_boundaries(energies, times)
    boundaries = [0.0] + boundaries + [duration]
    boundaries = sorted(set(boundaries))

    sections = []
    for i in range(len(boundaries) - 1):
        start = boundaries[i]
        end = boundaries[i + 1]

        segment_energies = [
            p.energy for p in energy_curve if start <= p.time < end
        ]
        if not segment_energies:
            continue

        avg_energy = sum(segment_energies) / len(segment_energies)
        section_type = _classify_section(
            avg_energy, start, end, duration, i, len(boundaries) - 1
        )
        energy_label = _energy_label(avg_energy)

        sections.append(
            Section(
                start=round(start, 2),
                end=round(end, 2),
                type=section_type,
                energy=energy_label,
            )
        )

    if len(sections) < 2:
        return _fallback_sections(energy_curve, duration)

    return sections[:8]


def _find_boundaries(energies: list[float], times: list[float]) -> list[float]:
    """Find section boundaries where energy changes significantly."""
    boundaries = []
    window = max(2, len(energies) // 8)

    for i in range(window, len(energies) - window):
        left_avg = sum(energies[i - window : i]) / window
        right_avg = sum(energies[i : i + window]) / window

        if left_avg > 0 and abs(right_avg - left_avg) / max(left_avg, 0.01) > 0.5:
            if not boundaries or (times[i] - boundaries[-1]) > 2.0:
                boundaries.append(times[i])

    return boundaries


def _classify_section(
    avg_energy: float,
    start: float,
    end: float,
    duration: float,
    index: int,
    total: int,
) -> str:
    is_start = start < duration * 0.15
    is_end = end > duration * 0.85

    if is_start and avg_energy < 0.4:
        return "intro"
    if is_end and avg_energy < 0.4:
        return "outro"
    if avg_energy > 0.7:
        return "drop"
    if avg_energy > 0.5:
        return "chorus"
    if index > 0 and avg_energy > 0.3:
        return "verse"
    return "build_up"


def _energy_label(avg_energy: float) -> str:
    if avg_energy < 0.35:
        return "low"
    if avg_energy < 0.65:
        return "medium"
    return "high"


def _fallback_sections(
    energy_curve: list[EnergyPoint], duration: float
) -> list[Section]:
    """Create at least 2 sections by splitting at the midpoint."""
    mid = duration / 2
    first_half = [p.energy for p in energy_curve if p.time < mid]
    second_half = [p.energy for p in energy_curve if p.time >= mid]

    avg1 = sum(first_half) / len(first_half) if first_half else 0.5
    avg2 = sum(second_half) / len(second_half) if second_half else 0.5

    return [
        Section(
            start=0.0,
            end=round(mid, 2),
            type="intro" if avg1 < 0.5 else "verse",
            energy=_energy_label(avg1),
        ),
        Section(
            start=round(mid, 2),
            end=round(duration, 2),
            type="outro" if avg2 < 0.5 else "drop",
            energy=_energy_label(avg2),
        ),
    ]
