package cn.zzs.hystrix;

public class DataResponse<T> extends Response {
    private T data;

    
    public T getData() {
        return data;
    }

    
    public void setData(T data) {
        this.data = data;
    }
    
    
    public static <T> DataResponse<T> buildSuccess(T data) {
        DataResponse<T> response = new DataResponse<>();
        response.setSuccess(true);
        response.setData(data);
        response.setCode("100000");
        response.setMessage("操作成功");
        return response;
    }
    
    
    public static <T> DataResponse<T> buildFailure(String message) {
        DataResponse<T> response = new DataResponse<>();
        response.setSuccess(false);
        response.setData(null);
        response.setCode("100001");
        response.setMessage(message);
        return response;
    }


    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("DataResponse [data=");
        builder.append(data);
        builder.append(", success=");
        builder.append(isSuccess());
        builder.append(", message=");
        builder.append(getMessage());
        builder.append(", code=");
        builder.append(getCode());
        builder.append("]");
        return builder.toString();
    }


}
