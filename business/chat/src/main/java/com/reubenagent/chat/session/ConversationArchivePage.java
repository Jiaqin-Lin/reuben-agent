package com.reubenagent.chat.session;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 会话分页结果。
 *
 * @author reuben
 * @since 2026-06-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationArchivePage {

    private long total;
    private int pageNo;
    private int pageSize;
    private List<ConversationArchiveRecord> records;
}
