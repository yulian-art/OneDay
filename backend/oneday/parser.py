"""Conservative, deterministic MVP parser. Unknown/compound requests require clarification."""

import re

RULES = {
    "return_and_deliver_toy": (
        r"玩具|toy|fetch",
        ["携带玩具接近主人", "玩具交到主人手中"],
        ["只把玩具放在主人脚边"],
    ),
    "drink_water": (r"喝水|饮水|drink", ["宠物在水源处饮水"], []),
    "approach_and_stay": (r"接近|靠近|停留|approach|stay", ["宠物接近主人", "宠物停留"], []),
    "jump": (r"跳跃|跳起|jump", ["宠物起跳", "宠物落地"], []),
}


class UnsupportedTask(ValueError):
    pass


def parse_task(text):
    lowered = text.lower()
    if re.search(r"开心|伤心|情绪|害怕|焦虑|生病|happy|sad|emotion|diagnos", lowered):
        raise UnsupportedTask("MVP 不支持情绪或健康诊断，请描述可观察动作")
    hits = [key for key, (pattern, _, _) in RULES.items() if re.search(pattern, lowered)]
    # 'Approach' can be a stage of toy delivery; other combined actions are not silently collapsed.
    if "return_and_deliver_toy" in hits and "approach_and_stay" in hits:
        hits.remove("approach_and_stay")
    if len(hits) != 1:
        raise UnsupportedTask("请指定一个动作：喝水、接近停留、跳跃或玩具交接")
    kind = hits[0]
    if re.search(r"不(?:要)?(?:喝水|饮水|跳跃|跳起|接近)|not\s+(drink|jump|approach)", lowered):
        raise UnsupportedTask("否定动作请拆分为正向动作与排除条件")
    _, stages, exclusions = RULES[kind]
    stages, exclusions = list(stages), list(exclusions)
    if kind == "return_and_deliver_toy" and re.search(r"脚边.*(?:都算|也算|也应该算|算符合)", text):
        exclusions = []
        stages[-1] = "玩具交到主人手中或放在主人脚边"
    return {
        "event_type": kind,
        "required_stages": stages,
        "exclusion_conditions": exclusions,
        "frame_requirements": {"key_area_visible": True, "min_duration": 2.0},
        "parser": "rules-v1",
        "requires_confirmation": True,
    }
